package com.amadeus.whale.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.model.AmadeusMood
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.theatre.DialogueBox
import com.amadeus.whale.theatre.OverlayHost
import com.amadeus.whale.theatre.TheatreStage
import com.amadeus.whale.theatre.TheatreViewModel
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 剧场（产品 1.8/架构 3.11）：Stage + DialogueBox + 输入角落唤出（占一行）+ 顶部操作 + OverlayHost。
 * 状态提升：全部状态从 TheatreViewModel 来，组件纯展示。
 */
@Composable
fun TheatreScreen(
  viewModel: TheatreViewModel,
  prefsStore: DevicePrefsStore,
  gatewayUrl: String?,
  onOpenSaveSlot: () -> Unit,
  onReplayDemo: () -> Unit,
  onDisconnect: () -> Unit,
  onReconnect: () -> Unit = {},
  demoMode: Boolean = false,
  onDemoFinished: () -> Unit = {},
) {
  val state by viewModel.uiState.collectAsState()
  val colors = LocalAmadeusColors.current
  var inputOpen by remember { mutableStateOf(false) }
  var inputText by remember { mutableStateOf("") }

  // demo 播完 → 回调 onDemoFinished（跳连接页）
  LaunchedEffect(state.demoFinished) {
    if (state.demoFinished) onDemoFinished()
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // 舞台：背景 + 立绘
    TheatreStage(
      background = state.background,
      mood = state.dialogue?.tag?.mood ?: AmadeusMood.idle,
      sprite = state.dialogue?.tag?.sprite ?: AmadeusSprite.smile,
    )
    // 底部：输入唤出（占一行）+ 对话框
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
    ) {
      // 输入栏（平时隐藏，角落按键唤出，仅占一行，架构 3.8/产品 1.8）
      if (inputOpen) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
          // 蕾丝输入框顶部装饰条（maid-atelier composer-frame，CC BY-NC-SA 4.0）
          com.amadeus.whale.theatre.AssetImage(
            name = "maid-composer-frame-v4",
            modifier = Modifier.fillMaxWidth().height(34.dp),
          )
          Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
              value = inputText,
              onValueChange = { inputText = it },
              modifier = Modifier.weight(1f),
              placeholder = { Text("和鲸鱼娘说点什么…") },
              singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = {
                if (inputText.isNotBlank()) {
                  viewModel.send(inputText)
                  inputText = ""
                  inputOpen = false
                }
              },
            ) { Text("发送") }
          }
        }
      }
      DialogueBox(
        speaker = state.speaker,
        text = state.dialogue?.text ?: "（等待鲸鱼娘说话…）",
        typing = state.typing,
        onClick = {
          viewModel.onTap()
          // 真实模式：点击对话框唤出输入框（demo 模式无输入）
          if (!demoMode) inputOpen = true
          if (demoMode && !state.typing) inputOpen = false
        },
      )
    }
    // 角落按键：唤出输入框（真实模式，架构 3.8/产品 1.8）
    if (!demoMode) {
      Box(modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
        IconButton(
          onClick = { inputOpen = !inputOpen },
          modifier = Modifier.background(colors.cardBackground, shape = androidx.compose.foundation.shape.CircleShape),
        ) {
          Text(if (inputOpen) "▼" else "✎", color = colors.primaryText)
        }
      }
    }
    // 顶部：历史/事件流 + 读档/设置
    Box(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
      Row {
        TextButton(onClick = { viewModel.openHistory() }) { Text("记录", color = colors.primaryText) }
        TextButton(onClick = { viewModel.openEventLog() }) { Text("幕后", color = colors.secondaryText) }
      }
    }
    Box(modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
      Row {
        if (!demoMode) TextButton(onClick = onOpenSaveSlot) { Text("读档", color = colors.primaryText) }
        TextButton(onClick = { viewModel.openOverlay(com.amadeus.whale.theatre.OverlayState.Settings) }) {
          Text("设置", color = colors.primaryText)
        }
      }
    }

    // 覆盖层（架构 3.12）
    OverlayHost(
      overlay = state.overlay,
      prefsStore = prefsStore,
      gatewayUrl = gatewayUrl,
      onClose = { viewModel.closeOverlay() },
      onReplayDemo = onReplayDemo,
      onDisconnect = onDisconnect,
      onReconnect = onReconnect,
      onResolveChoice = { choice, label -> viewModel.resolveChoice(choice, label) },
      onDecideApproval = { approval, allowed -> viewModel.decideApproval(approval, allowed) },
    )
  }
}
