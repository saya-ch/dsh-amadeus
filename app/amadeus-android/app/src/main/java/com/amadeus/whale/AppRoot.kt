package com.amadeus.whale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amadeus.whale.feed.DemoFeed
import com.amadeus.whale.feed.RealFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusWindow
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import com.amadeus.whale.network.PreviewPayload
import com.amadeus.whale.network.ReportPayload
import com.amadeus.whale.network.StreamEvent
import com.amadeus.whale.pairing.AmadeusAuthClient
import com.amadeus.whale.pairing.AuthResult
import com.amadeus.whale.pairing.GatewayOrigin
import com.amadeus.whale.pairing.PairingService
import com.amadeus.whale.saveslot.SaveSlotScreen
import com.amadeus.whale.saveslot.SaveSlotViewModel
import com.amadeus.whale.settings.SettingsScreen
import com.amadeus.whale.settings.SettingsViewModel
import com.amadeus.whale.theatre.AmbientSound
import com.amadeus.whale.theatre.ChoiceUi
import com.amadeus.whale.theatre.TheatreScreen
import com.amadeus.whale.theatre.TheatreUiState
import com.amadeus.whale.theatre.TheatreViewModel
import com.amadeus.whale.window.ChoiceWindow
import com.amadeus.whale.window.HistoryWindow
import com.amadeus.whale.window.PreviewWindow
import com.amadeus.whale.window.ReportWindow
import com.amadeus.whale.window.trapTaps
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun AppRoot(prefs: AmadeusPrefs, sound: AmbientSound, pairing: PairingService, authClient: AmadeusAuthClient) {
  var screen by remember { mutableStateOf(Screen.Demo) }
  var selectedSessionId by remember { mutableStateOf<String?>(null) }
  var settingsOpen by remember { mutableStateOf(false) }
  var settingsBaseUrl by remember { mutableStateOf<String?>(null) }
  var historyOpen by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val baseClient = remember {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }
  var sessionClient by remember { mutableStateOf<OkHttpClient?>(null) }
  val settingsApiProvider = remember(baseClient) { { url: String -> AmadeusApi(url, baseClient) } }

  LaunchedEffect(Unit) {
    val saved = prefs.baseUrl
    if (saved != null) {
      val origin = runCatching { GatewayOrigin.parse(saved) }.getOrNull()
      if (origin != null) {
        when (val r = pairing.restore(origin)) {
          is AuthResult.Success -> {
            sessionClient = r.client
            screen = Screen.Real
          }
          is AuthResult.Failure -> {
            prefs.clear()
            sessionClient = null
            screen = Screen.Demo
          }
        }
        return@LaunchedEffect
      } else {
        prefs.clear()
      }
    }
    screen = Screen.Demo
  }

  // 环境音仅 demo：离开 demo（进入真实模式）时停掉正在循环的 BGM/SFX
  LaunchedEffect(screen) {
    if (screen != Screen.Demo) sound.stopAll()
  }

  val settingsVm = remember { SettingsViewModel(prefs, settingsApiProvider, pairing) }
  val openSettings: () -> Unit = {
    settingsBaseUrl = prefs.baseUrl
    settingsOpen = true
  }
  if (settingsOpen) {
    SettingsScreen(viewModel = settingsVm, isRealMode = screen == Screen.Real, onDone = {
      settingsOpen = false
      scope.launch {
        if (prefs.baseUrl == null) {
          settingsBaseUrl?.let { old ->
            runCatching { GatewayOrigin.parse(old) }.getOrNull()?.let { authClient.clear(it) }
          }
          sessionClient = null
          selectedSessionId = null
          screen = Screen.Demo
          return@launch
        }
        // 同步配对成功后的 session client（若有）
        settingsVm.lastSessionClient?.let { sessionClient = it }
        if (screen != Screen.Real) {
          if (sessionClient == null) {
            val origin = runCatching { GatewayOrigin.parse(prefs.baseUrl!!) }.getOrNull()
            if (origin != null) {
              when (val r = pairing.restore(origin)) {
                is AuthResult.Success -> sessionClient = r.client
                is AuthResult.Failure -> {
                  prefs.clear()
                  sessionClient = null
                  screen = Screen.Demo
                  return@launch
                }
              }
            }
          }
          screen = Screen.Real
        } else if (settingsBaseUrl != prefs.baseUrl) {
          // 网关已变更：丢弃旧网关上的会话与 SSE 连接，回到选档按新网关重新进入
          selectedSessionId = null
          settingsBaseUrl?.let { old ->
            runCatching { GatewayOrigin.parse(old) }.getOrNull()?.let { authClient.clear(it) }
          }
          if (sessionClient == null) {
            settingsVm.lastSessionClient?.let { sessionClient = it }
          }
          if (sessionClient == null) {
            val origin = runCatching { GatewayOrigin.parse(prefs.baseUrl!!) }.getOrNull()
            if (origin != null) {
              when (val r = pairing.restore(origin)) {
                is AuthResult.Success -> sessionClient = r.client
                is AuthResult.Failure -> { /* keep without session, fallback to base */ }
              }
            }
          }
        } else {
          // 同一网关下可能通过配对刷新了凭据，同步 client
          settingsVm.lastSessionClient?.let { sessionClient = it }
        }
      }
    })
    return
  }

  when (screen) {
    Screen.Demo -> {
      val vm = remember { TheatreViewModel(DemoFeed(), { "palace-night" }) }
      val state by vm.uiState.collectAsState()
      LaunchedEffect(Unit) { vm.load(); sound.playBgm("rain"); sound.play("wave") }
      TheatreScreen(
        viewModel = vm,
        backgroundResolver = { "palace-night" },
        onOpenSettings = openSettings,
      )
      WindowOverlayHost(
        api = null,
        state = state,
        onSelectChoice = { _, _ -> scope.launch { vm.dismissChoice() } },
        onDismissChoice = { vm.dismissChoice() },
      )
    }
    Screen.Real -> {
      val effectiveClient = sessionClient ?: baseClient
      val saveVm = remember(prefs.baseUrl, effectiveClient) { SaveSlotViewModel(AmadeusApi(prefs.baseUrl!!, effectiveClient)) }
      LaunchedEffect(prefs.baseUrl, effectiveClient) { saveVm.load() }
      selectedSessionId?.let { sessionId ->
        val api = AmadeusApi(prefs.baseUrl!!, effectiveClient)
        val stream = remember(prefs.baseUrl, effectiveClient) { AmadeusStream(prefs.baseUrl!!, effectiveClient) }
        val realFeed = remember(sessionId, prefs.baseUrl, effectiveClient) { RealFeed(sessionId, api, stream) }
        val vm = remember(sessionId, prefs.baseUrl) {
          TheatreViewModel(realFeed, backgroundResolver = { mood ->
            if (mood == AmadeusMood.tool || mood == AmadeusMood.think) "bg-gpt-collaboration-workshop"
            else "bg-claude-writing-study"
          })
        }
        val state by vm.uiState.collectAsState()
        LaunchedEffect(sessionId, prefs.baseUrl) { vm.load() }
        DisposableEffect(sessionId, prefs.baseUrl) {
          val closer = realFeed.attach { event ->
            when (event) {
              is StreamEvent.Segments -> event.list.forEach { vm.enqueue(it) }
              is StreamEvent.Choice -> vm.showChoice(event)
              is StreamEvent.Ended -> vm.markIdle()
            }
          }
          onDispose { closer.close() }
        }
        TheatreScreen(
          viewModel = vm,
          backgroundResolver = vm.backgroundResolverFor,
          onOpenSettings = openSettings,
          onOpenHistory = { historyOpen = true },
          inputBar = { InputBar(onSend = { text -> scope.launch { vm.sendToFeed(text) } }) },
        )
        WindowOverlayHost(
          api = api,
          state = state,
          onSelectChoice = { c, label ->
            scope.launch {
              runCatching { api.resolveChoice(c.choiceId, label) }
              vm.dismissChoice()
            }
          },
          onDismissChoice = {
            // 用户关闭 choice 窗口 = 取消：通知 host 其 wait(choiceId) 应解除
            state.choice?.let { c ->
              scope.launch { runCatching { api.cancelChoice(c.choiceId) } }
            }
            vm.dismissChoice()
          },
        )
        if (historyOpen) {
          HistoryWindow(sessionId = sessionId, api = api, onClose = { historyOpen = false })
        }
      } ?: run {
        SaveSlotScreen(
          viewModel = saveVm,
          onOpen = { selectedSessionId = it },
          onChangeConnection = openSettings,
          onNewSession = {
            scope.launch { saveVm.create()?.let { selectedSessionId = it.id } }
          },
        )
      }
    }
  }
}

@Composable
private fun WindowOverlayHost(
  api: AmadeusApi?,
  state: TheatreUiState,
  onSelectChoice: (ChoiceUi, String) -> Unit,
  onDismissChoice: () -> Unit,
) {
  val windowId = state.windowId
  val windowType = state.windowType
  if (windowId != null && (windowType == AmadeusWindow.report || windowType == AmadeusWindow.preview)) {
    var dismissed by remember(windowId, windowType) { mutableStateOf(false) }
    if (!dismissed) {
      when (windowType) {
        AmadeusWindow.report -> {
          var payload by remember(windowId) { mutableStateOf<ReportPayload?>(null) }
          var failed by remember(windowId) { mutableStateOf(false) }
          LaunchedEffect(windowId) {
            val r = if (api != null) runCatching { api.getReport(windowId) }.getOrNull()
            else ReportPayload(windowId, "今日小报告",
              "（演示模式）\n\n干完活就化成小报告放在这里。\n\n连接网关后，这里会展示模型生成的真实报告。\n\n- 报告窗口可滚动\n- 文件列表行用等宽字体", 0L)
            if (r != null) payload = r else failed = true
          }
          when {
            payload != null -> ReportWindow(report = payload!!, onClose = { dismissed = true })
            failed -> WindowLoadError("报告加载失败")
            else -> WindowLoading()
          }
        }
        AmadeusWindow.preview -> {
          var payload by remember(windowId) { mutableStateOf<PreviewPayload?>(null) }
          var failed by remember(windowId) { mutableStateOf(false) }
          LaunchedEffect(windowId) {
            val r = if (api != null) runCatching { api.getPreview(windowId) }.getOrNull()
            else PreviewPayload(windowId, "text", "（演示模式）预览内容占位。", "预览")
            if (r != null) payload = r else failed = true
          }
          when {
            payload != null -> PreviewWindow(preview = payload!!, onClose = { dismissed = true })
            failed -> WindowLoadError("预览加载失败")
            else -> WindowLoading()
          }
        }
        else -> Unit
      }
    }
  }
  val choice = state.choice
  if (choice != null) {
    ChoiceWindow(
      choiceId = choice.choiceId,
      question = choice.question,
      options = choice.options,
      onSelect = { label -> onSelectChoice(choice, label) },
      onClose = onDismissChoice,
    )
  }
}

@Composable
private fun WindowLoading() {
  Surface(Modifier.fillMaxSize().trapTaps()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
  }
}

@Composable
private fun WindowLoadError(message: String) {
  Surface(Modifier.fillMaxSize().trapTaps()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(message, color = Color(0xFF444444))
    }
  }
}

@Composable
private fun InputBar(onSend: (String) -> Unit) {
  var text by remember { mutableStateOf("") }
  Row(Modifier.fillMaxWidth().padding(8.dp)) {
    OutlinedTextField(
      value = text, onValueChange = { text = it },
      modifier = Modifier.weight(1f), placeholder = { Text("和鲸鱼娘说点什么…") },
    )
    Button(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }) { Text("发送") }
  }
}

private enum class Screen { Demo, Real }
