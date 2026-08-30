package com.amadeus.whale.ui.theatre

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/**
 * 打字机效果：逐字显示对话框
 */
@Composable
fun rememberTypewriter(text: String, speedMs: Long = 30): String {
  var displayed by remember { mutableStateOf("") }
  LaunchedEffect(text) {
    displayed = ""
    for (i in text.indices) {
      displayed = text.substring(0, i + 1)
      delay(speedMs)
    }
  }
  return displayed
}
