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
 * 连接剧场（产品 1.12 / 架构 3.16）：鲸鱼娘等你接入。
 * - 背景 + 立绘 + 演出"等你接入"（配对中/成功反馈）
 * - 扫码配对（ScanActivity）
 * - 首次配对引导（demo 后）vs 日常重连（非 demo）去向不同
 */
@Composable
fun ConnectionScreen(
  firstPairing: Boolean,
  onPaired: (sessionId: String?) -> Unit,
  onBackToDemo: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  Box(modifier = Modifier.fillMaxSize().background(colors.screenBackground)) {
    Column(
      modifier = Modifier.align(Alignment.Center).padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // TODO: 背景 + 立绘（鲸鱼娘等你接入，产品 1.12/架构 3.16）
      Text(text = "🐳", fontSize = 72.sp)
      Text(
        text = if (firstPairing) "呜…第一次见面，请把我接到你的电脑上吧" else "呜…好像还没连上你的电脑呢",
        color = colors.primaryText,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(24.dp))
      Button(onClick = { /* TODO: 扫码配对（ScanActivity） */ }) {
        Text("扫码配对")
      }
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = { /* TODO: 手动输入地址 */ }) {
        Text("手动输入地址")
      }
      if (firstPairing) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBackToDemo) {
          Text("再看一遍 demo")
        }
      }
    }
  }
}
