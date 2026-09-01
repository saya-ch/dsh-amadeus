package com.amadeus.whale.root

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.data.store.DevicePrefs
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.domain.LaunchTarget
import com.amadeus.whale.screen.ConnectionScreen
import com.amadeus.whale.screen.SaveSlotScreen
import com.amadeus.whale.screen.TheatreScreen
import com.amadeus.whale.screen.TitleScreen
import com.amadeus.whale.theme.AmadeusTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * App 根：主题注入 + 页面路由 + 启动决策（架构 3.1/3.13）。
 * 覆盖层（设置/窗口/小窗）由各 Screen 内部 OverlayHost 管，不走这里。
 */
@Composable
fun AppRoot(
  prefsStore: DevicePrefsStore,
  launchDecider: AppLaunchDecider,
) {
  val prefs by prefsStore.flow.collectAsState(initial = DevicePrefs())
  var screen by remember { mutableStateOf<Screen>(Screen.Title) }
  var booted by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // 启动决策：标题画面展示期间读状态，决策完切到目标 Screen
  LaunchedEffect(Unit) {
    val target = launchDecider.decide()
    screen = when (target) {
      is LaunchTarget.FirstRunDemo -> Screen.Demo
      is LaunchTarget.ConnectDaily -> Screen.Connection(firstPairing = false)
      is LaunchTarget.RealTheatre -> Screen.Theatre(target.sessionId)
      is LaunchTarget.ConnectAfterFailure -> Screen.Connection(firstPairing = false)
    }
    booted = true
  }

  AmadeusTheme(themeId = prefs.themeId) {
    when (val s = screen) {
      Screen.Title -> TitleScreen(onFinish = { /* 决策完自动切走 */ })
      Screen.Demo -> DemoTheatrePlaceholder(onEnterReal = {
        // demo 播完 → 首次配对引导（产品 1.12）
        scope.launch { prefsStore.setDemoSeen(true) }
        screen = Screen.Connection(firstPairing = true)
      })
      is Screen.Connection -> ConnectionScreen(
        firstPairing = s.firstPairing,
        onPaired = { sessionId ->
          // 首次配对 → 新建会话（"她刚住进来"）；日常重连 → 最后活动会话
          if (s.firstPairing) scope.launch { prefsStore.setDemoSeen(true) }
          screen = if (sessionId != null) Screen.Theatre(sessionId) else Screen.SaveSlot
        },
        onBackToDemo = { screen = Screen.Demo },
      )
      Screen.SaveSlot -> SaveSlotScreen(
        onOpenSession = { sessionId -> screen = Screen.Theatre(sessionId) },
        onBack = { screen = Screen.Connection(firstPairing = false) },
      )
      is Screen.Theatre -> TheatreScreen(
        sessionId = s.sessionId,
        onOpenSaveSlot = { screen = Screen.SaveSlot },
        onDisconnect = { scope.launch { prefsStore.setGatewayUrl(null); screen = Screen.Connection(firstPairing = false) } },
      )
    }
  }
}

/** 骨架阶段占位：demo 剧场（后续填充真实 demo 剧本 + 演出）。 */
@Composable
private fun DemoTheatrePlaceholder(onEnterReal: () -> Unit) {
  androidx.compose.material3.Surface {
    androidx.compose.material3.Text(
      "Demo 剧场（骨架占位）\n\n点击进入配对引导",
      modifier = androidx.compose.ui.Modifier.padding(32.dp),
    )
    // TODO: 填充 DemoFeed + TheatreStage + DialogueBox
  }
}
