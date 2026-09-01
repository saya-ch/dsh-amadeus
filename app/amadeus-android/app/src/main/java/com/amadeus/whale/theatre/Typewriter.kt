package com.amadeus.whale.theatre

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.amadeus.whale.theme.AmadeusMotion
import kotlinx.coroutines.delay

/**
 * 打字机（产品 1.5.2 / 架构 3.8）：
 * - 基准字速 45ms/字（三档：慢 60 / 中 45 / 快 30）
 * - 标点额外停顿：逗号/顿号 +90ms、句号/叹号/问号 +135ms、省略号 +225ms、破折号 +90ms
 * - typing=false 时立即打满（打断打字）
 */
@Composable
fun Typewriter(
  text: String,
  typing: Boolean,
  modifier: Modifier = Modifier,
  speed: Int = 1, // 0=慢 1=中 2=快
) {
  var shown by remember(text) { mutableIntStateOf(0) }
  LaunchedEffect(text, typing, speed) {
    if (!typing) { shown = text.length; return@LaunchedEffect }
    val baseMs = when (speed) { 0 -> 60; 2 -> 30; else -> 45 }
    shown = 0
    for (i in 1..text.length) {
      shown = i
      val ch = text[i - 1]
      var wait = baseMs
      when {
        ch == '，' || ch == '、' || ch == '；' -> wait += 90
        ch == '。' || ch == '！' || ch == '？' -> wait += 135
        ch == '…' -> wait += 225
        ch == '—' -> wait += 90
      }
      delay(wait.toLong())
    }
  }
  BasicText(text = text.take(shown), modifier = modifier)
}
