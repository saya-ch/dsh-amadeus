package com.amadeus.whale.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theatre.DialogueBox
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 剧场（产品 1.8/架构 3.11）：背景 + 立绘 + 对话框 + 输入唤出 + 覆盖层。
 * 骨架阶段：基本结构（背景占位 + 对话框 + 切换读档/断开），后续填充 Stage/InputBar/OverlayHost。
 */
@Composable
fun TheatreScreen(
  sessionId: String,
  onOpenSaveSlot: () -> Unit,
  onDisconnect: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  var dialog by remember { mutableStateOf("鲸鱼娘在这里等你的任务…（骨架占位）") }
  var typing by remember { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    // TODO: TheatreStage（背景 + 立绘，架构 3.11）
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxSize().padding(16.dp),
    ) {
      Spacer(Modifier.weight(1f))
      DialogueBox(
        speaker = "鲸鱼娘",
        text = dialog,
        typing = typing,
        onClick = { /* TODO: 打断打字/立即打满 */ },
      )
    }
    // 顶部：切换读档 + 断开（骨架占位，后续收进设置/顶部栏）
    Box(modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
      TextButton(onClick = onOpenSaveSlot) { Text("读档", color = colors.primaryText) }
    }
    Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
      TextButton(onClick = onDisconnect) { Text("断开", color = colors.danger) }
    }
  }
}
