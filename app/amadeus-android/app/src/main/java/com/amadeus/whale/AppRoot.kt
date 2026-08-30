package com.amadeus.whale

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amadeus.whale.feed.DemoFeed
import com.amadeus.whale.feed.RealFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import com.amadeus.whale.network.StreamEvent
import com.amadeus.whale.saveslot.SaveSlotScreen
import com.amadeus.whale.saveslot.SaveSlotViewModel
import com.amadeus.whale.settings.SettingsScreen
import com.amadeus.whale.settings.SettingsViewModel
import com.amadeus.whale.theatre.AmbientSound
import com.amadeus.whale.theatre.TheatreScreen
import com.amadeus.whale.theatre.TheatreViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Composable
fun AppRoot(prefs: AmadeusPrefs, sound: AmbientSound) {
  var screen by remember { mutableStateOf(Screen.Demo) }
  var selectedSessionId by remember { mutableStateOf<String?>(null) }
  var settingsOpen by remember { mutableStateOf(false) }
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
      LaunchedEffect(Unit) { vm.load(); sound.playBgm("rain"); sound.play("wave") }
      TheatreScreen(
        viewModel = vm,
        backgroundResolver = { "palace-night" },
        onOpenSettings = { settingsOpen = true },
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
          inputBar = { send -> InputBar(onSend = { text -> scope.launch { vm.sendToFeed(text) } }) },
        )
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