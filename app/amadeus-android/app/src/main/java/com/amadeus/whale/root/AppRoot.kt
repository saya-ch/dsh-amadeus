package com.amadeus.whale.root

import androidx.compose.runtime.Composable
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
import com.amadeus.whale.screen.ConnectionScreen
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
  LaunchedEffect(Unit) {
    val target = launchDecider.decide()
    screen = when (target) {
      is LaunchTarget.FirstRunDemo -> Screen.Demo
      is LaunchTarget.ConnectDaily -> Screen.Connection(firstPairing = false)
      is LaunchTarget.RealTheatre -> Screen.Theatre(target.sessionId)
      is LaunchTarget.ConnectAfterFailure -> Screen.Connection(firstPairing = false)
    }
  }

  AmadeusTheme(themeId = prefs.themeId) {
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
          // 配对成功 → 从 auth 构造真实 repository → 进剧场/读档
          if (s.firstPairing) scope.launch { prefsStore.setDemoSeen(true) }
          repository = authService.currentSession()?.let {
            HttpSessionRepository(it.origin.serialized, it.client)
          }
          scope.launch { prefsStore.setGatewayUrl(authService.currentSession()?.origin?.serialized) }
          screen = if (sessionId != null) Screen.Theatre(sessionId) else Screen.SaveSlot
        },
        onBackToDemo = { screen = Screen.Demo },
      )
      Screen.SaveSlot -> SaveSlotScreen(
        repository = repository ?: return@AmadeusTheme,
        onOpenSession = { sessionId -> screen = Screen.Theatre(sessionId) },
        onBack = { screen = Screen.Connection(firstPairing = false) },
      )
      is Screen.Theatre -> RealTheatreHost(
        sessionId = s.sessionId,
        repository = repository ?: return@AmadeusTheme,
        prefsStore = prefsStore,
        authService = authService,
        onOpenSaveSlot = { screen = Screen.SaveSlot },
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
  LaunchedEffect(sessionId) {
    // 同步触觉开关（产品 1.10：设置里可关）
    vm.setHapticsEnabled(prefsStore.flow.first().hapticsEnabled)
    // 恢复：历史 10 条 → 最新一条文本为当前展示（架构 3.20）
    val page = repository.page(sessionId)
    page.events.forEach { vm.onStreamEvent(it) }
    // 注入发送器
    vm.setSender { text -> scope.launch { repository.send(sessionId, text) } }
    // 打开 SSE 续接实时（架构 3.19）
    val closer = repository.openStream(sessionId) { event -> vm.onStreamEvent(event) }
    vm.onStreamEvent(com.amadeus.whale.domain.model.StreamEvent.Ended("stream_ready"))
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
