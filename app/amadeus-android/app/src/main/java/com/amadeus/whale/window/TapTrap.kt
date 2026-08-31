package com.amadeus.whale.window

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

// 窗口叠在舞台之上，吃掉落在窗口背景上的按下事件，避免穿透到下层舞台的 tap-to-advance
fun Modifier.trapTaps(): Modifier = this.pointerInput(Unit) {
  awaitEachGesture { awaitFirstDown().consume() }
}