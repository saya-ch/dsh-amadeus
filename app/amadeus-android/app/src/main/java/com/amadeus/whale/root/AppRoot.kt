package com.amadeus.whale.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.amadeus.whale.data.HttpAuthService
import com.amadeus.whale.data.HttpChoiceRepository
import com.amadeus.whale.data.HttpSessionRepository
import com.amadeus.whale.data.store.DevicePrefs
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.domain.ChoiceRepository
import com.amadeus.whale.domain.DemoFeed
import com.amadeus.whale.domain.LaunchTarget
import com.amadeus.whale.domain.SessionRepository
import com.amadeus.whale.platform.BgmPlayerEffect
import com.amadeus.whale.screen.ConnectionScreen
import com.amadeus.whale.screen.ConnectedScreen
import com.amadeus.whale.screen.SaveSlotScreen
import com.amadeus.whale.screen.TheatreScreen
import com.amadeus.whale.screen.TitleScreen
import com.amadeus.whale.theatre.TheatreViewModel
import com.amadeus.whale.theme.AmadeusTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * App 根：主题注入 + 页面路由 + 启动决策（架构 3.1/3.13）。
 * 覆盖层（设置/窗口/小窗）由各 Screen 内部 OverlayHost 管，不走这里。
 */
@Composable
fun AppRoot(
  prefsStore: DevicePrefsStore,
  launchDecider: AppLaunchDecider,
  authService: HttpAuthService,
) {
  val prefs by prefsStore.flow.collectAsState(initial = DevicePrefs())
  var screen by remember { mutableStateOf<Screen>(Screen.Title) }
  val scope = rememberCoroutineScope()

  // 真实会话 repository：从配对成功的 session client 构造（baseUrl = 网关 origin）
  var repository by remember { mutableStateOf<SessionRepository?>(null) }

  // 启动决策：标题画面展示期间读状态，决策完切到目标 Screen
  // （Default dispatcher：不占渲染主线程；决策完成前 Title 正常淡入）
  LaunchedEffect(Unit) {
    android.util.Log.d("AMW", "launch: decide start")
    kotlinx.coroutines.delay(600) // 让 Title 先渲染一帧（logo 淡入开始）
    val target = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      try {
        val t = launchDecider.decide()
        android.util.Log.d("AMW", "launch: decide done -> $t")
        t
      } catch (error: Exception) {
        android.util.Log.d("AMW", "launch: decide error ${error.message}")
        // 启动决策异常兜底：绝不黑屏，降级到连接页
        LaunchTarget.ConnectDaily
      }
    }
    // restore 成功（RealTheatre 目标）时 currentSession 已就绪 → 备好 repository
    if (target is LaunchTarget.RealTheatre) {
      repository = authService.currentSession()?.let {
        HttpSessionRepository(it.origin.serialized, it.client)
      }
    }
    screen = when (target) {
      is LaunchTarget.FirstRunDemo -> Screen.Demo
      is LaunchTarget.ConnectDaily -> Screen.Connection(firstPairing = false)
      // 恢复成功也先进 Connected 中间页（用户选继续/读档），不裸跳剧场
      is LaunchTarget.RealTheatre -> Screen.Connected(lastSessionId = target.sessionId)
      is LaunchTarget.ConnectAfterFailure -> Screen.Connection(firstPairing = false)
    }
    android.util.Log.d("AMW", "launch: screen -> ${screen}")
  }

  AmadeusTheme(themeId = prefs.themeId) {
    // BGM（产品 1.11：全 App 生效，随 bgmEnabled/音量即时响应）
    BgmPlayerEffect(bgmEnabled = prefs.bgmEnabled, bgmVolume = prefs.bgmVolume)
    when (val s = screen) {
      Screen.Title -> TitleScreen()
      Screen.Demo -> {
        // demo：本地剧本 + 剧场
        val vm = remember { TheatreViewModel() }
        LaunchedEffect(Unit) {
          vm.loadDemo(DemoFeed.initial(), "palace-night")
        }
        TheatreScreen(
          viewModel = vm,
          prefsStore = prefsStore,
          gatewayUrl = null,
          onOpenSaveSlot = {},
          onReplayDemo = {},
          onDisconnect = {},
          demoMode = true,
          onDemoFinished = {
            scope.launch { prefsStore.setDemoSeen(true) }
            screen = Screen.Connection(firstPairing = true)
          },
        )
      }
      is Screen.Connection -> ConnectionScreen(
        firstPairing = s.firstPairing,
        authService = authService,
        onPaired = { sessionId ->
          // 配对成功 → 从 auth 构造真实 repository → 连接成功中间页（不裸跳存档）
          if (s.firstPairing) scope.launch { prefsStore.setDemoSeen(true) }
          repository = authService.currentSession()?.let {
            HttpSessionRepository(it.origin.serialized, it.client)
          }
          scope.launch { prefsStore.setGatewayUrl(authService.currentSession()?.origin?.serialized) }
          screen = Screen.Connected(lastSessionId = sessionId)
        },
        onBackToDemo = { screen = Screen.Demo },
      )
      is Screen.Connected -> ConnectedScreen(
        repository = repository ?: return@AmadeusTheme,
        lastSessionId = s.lastSessionId,
        onContinue = { sessionId ->
          scope.launch { prefsStore.setLastSessionId(sessionId) }
          screen = Screen.Theatre(sessionId)
        },
        onOpenSaveSlot = { screen = Screen.SaveSlot(backTo = s) },
      )
      is Screen.SaveSlot -> SaveSlotScreen(
        repository = repository ?: return@AmadeusTheme,
        onOpenSession = { sessionId ->
          scope.launch { prefsStore.setLastSessionId(sessionId) }
          screen = Screen.Theatre(sessionId)
        },
        // 返回 = 回来源（剧场/Connected），不丢会话上下文
        onBack = { screen = s.backTo },
      )
      is Screen.Theatre -> RealTheatreHost(
        sessionId = s.sessionId,
        repository = repository ?: return@AmadeusTheme,
        prefsStore = prefsStore,
        authService = authService,
        onOpenSaveSlot = { screen = Screen.SaveSlot(backTo = s) },
        onReconnect = { screen = Screen.Connection(firstPairing = false) },
        onDisconnect = {
          scope.launch {
            authService.currentSession()?.let { authService.disconnect(it.origin.serialized) }
            prefsStore.setGatewayUrl(null)
          }
          repository = null
          screen = Screen.Connection(firstPairing = false)
        },
      )
    }
  }
}

/** 真实剧场宿主：接 Repository + SSE。 */
@Composable
private fun RealTheatreHost(
  sessionId: String,
  repository: SessionRepository,
  prefsStore: DevicePrefsStore,
  authService: HttpAuthService,
  onOpenSaveSlot: () -> Unit,
  onReconnect: () -> Unit,
  onDisconnect: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val vm = remember(sessionId) {
    val origin = authService.currentSession()?.origin?.serialized ?: ""
    val client = authService.currentSession()?.client ?: okhttp3.OkHttpClient()
    TheatreViewModel(
      choiceRepository = com.amadeus.whale.data.HttpChoiceRepository(origin, client),
      approvalRepository = com.amadeus.whale.data.HttpApprovalRepository(origin, client),
      haptics = com.amadeus.whale.platform.Haptics(context),
    )
  }
  val scope = rememberCoroutineScope()
  // 当前会话的 SSE 句柄：进剧场打开，离开剧场 dispose 关闭（防泄漏重复推事件）
  var streamCloser by remember { mutableStateOf<AutoCloseable?>(null) }
  LaunchedEffect(sessionId) {
    android.util.Log.d("AMW", "theatre host: resume $sessionId")
    // 同步触觉开关（产品 1.10：设置里可关）
    vm.setHapticsEnabled(prefsStore.flow.first().hapticsEnabled)
    // 恢复：历史 10 条 → 最新一条文本为当前展示（架构 3.20）
    val page = repository.page(sessionId)
    if (page.events.isEmpty()) {
      // 全新会话：本地默认开场（不进 agent，纯门面演出）
      vm.showLocalLine("今天想做点什么呢？", com.amadeus.whale.domain.model.AmadeusSprite.normal)
    } else {
      page.events.forEach { vm.onStreamEvent(it) }
    }
    // 注入发送器
    vm.setSender { text -> scope.launch { repository.send(sessionId, text) } }
    // 打开 SSE 续接实时（架构 3.19）
    val closer = repository.openStream(sessionId) { event -> vm.onStreamEvent(event) }
    streamCloser = closer
    vm.onStreamEvent(com.amadeus.whale.domain.model.StreamEvent.Ended("stream_ready"))
    android.util.Log.d("AMW", "theatre host: resume done")
  }
  DisposableEffect(sessionId) {
    onDispose {
      // 离开剧场（去读档/设置/断开）时关掉 SSE，避免残留连接重复推同一事件
      streamCloser?.close()
      streamCloser = null
    }
  }
  TheatreScreen(
    viewModel = vm,
    prefsStore = prefsStore,
    gatewayUrl = authService.currentSession()?.origin?.serialized,
    onOpenSaveSlot = onOpenSaveSlot,
    onReplayDemo = { /* demo 重放走路由，AppRoot 处理 */ },
    onDisconnect = onDisconnect,
    onReconnect = onReconnect,
  )
}
