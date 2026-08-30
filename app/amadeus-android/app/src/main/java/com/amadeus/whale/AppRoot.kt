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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun AppRoot(prefs: AmadeusPrefs, sound: AmbientSound) {
  var screen by remember { mutableStateOf(Screen.Demo) }
  var selectedSessionId by remember { mutableStateOf<String?>(null) }
  var settingsOpen by remember { mutableStateOf(false) }
  var historyOpen by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  val client = remember {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }
  val apiOf = remember { { url: String -> AmadeusApi(url, client) } }

  LaunchedEffect(Unit) {
    val saved = prefs.baseUrl
    if (saved != null && runCatching { apiOf(saved).health() }.getOrDefault(false)) {
      screen = Screen.Real
    } else {
      screen = Screen.Demo
    }
  }

  val settingsVm = remember { SettingsViewModel(prefs, apiOf) }
  if (settingsOpen) {
    SettingsScreen(viewModel = settingsVm, isRealMode = screen == Screen.Real, onDone = {
      settingsOpen = false
      if (prefs.baseUrl == null) {
        selectedSessionId = null
        screen = Screen.Demo
      } else if (screen != Screen.Real) {
        screen = Screen.Real
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
        onOpenSettings = { settingsOpen = true },
      )
      WindowOverlayHost(
        api = null,
        state = state,
        onSelectChoice = { _, _ -> scope.launch { vm.dismissChoice() } },
        onDismissChoice = { vm.dismissChoice() },
      )
    }
    Screen.Real -> {
      val saveVm = remember { SaveSlotViewModel(apiOf(prefs.baseUrl!!)) }
      LaunchedEffect(Unit) { saveVm.load() }
      selectedSessionId?.let { sessionId ->
        val api = apiOf(prefs.baseUrl!!)
        val stream = remember(prefs.baseUrl) { AmadeusStream(prefs.baseUrl!!, client) }
        val realFeed = remember(sessionId) { RealFeed(sessionId, api, stream) }
        val vm = remember(sessionId) {
          TheatreViewModel(realFeed, backgroundResolver = { mood ->
            if (mood == AmadeusMood.tool || mood == AmadeusMood.think) "bg-gpt-collaboration-workshop"
            else "bg-claude-writing-study"
          })
        }
        val state by vm.uiState.collectAsState()
        LaunchedEffect(sessionId) { vm.load() }
        DisposableEffect(sessionId) {
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
          onOpenSettings = { settingsOpen = true },
          onOpenHistory = { historyOpen = true },
          inputBar = { send -> InputBar(onSend = { text -> scope.launch { vm.sendToFeed(text) } }) },
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
          onDismissChoice = { vm.dismissChoice() },
        )
        if (historyOpen) {
          HistoryWindow(sessionId = sessionId, api = api, onClose = { historyOpen = false })
        }
      } ?: run {
        SaveSlotScreen(
          viewModel = saveVm,
          onOpen = { selectedSessionId = it },
          onChangeConnection = { settingsOpen = true },
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
  Surface(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
  }
}

@Composable
private fun WindowLoadError(message: String) {
  Surface(Modifier.fillMaxSize()) {
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