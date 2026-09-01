package com.amadeus.whale.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 读档页（产品 1.10 / 架构 3.15）：按工作区分组 + 组内倒序 + 新建仪式感 + 长按改名/删除。
 * 骨架阶段先占位，后续填充真实会话列表（SessionRepository）。
 */
@Composable
fun SaveSlotScreen(
  onOpenSession: (String) -> Unit,
  onBack: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    Column(
      modifier = Modifier.align(Alignment.Center).padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = "读档页（骨架占位）\n\n按工作区分组 + 会话卡片 + 新建“开启新的一天”",
        color = colors.primaryText,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(24.dp))
      Button(onClick = { /* TODO: 新建会话（"开启新的一天"） */ }) {
        Text("＋ 开启新的一天")
      }
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = onBack) { Text("返回连接") }
    }
  }
}
