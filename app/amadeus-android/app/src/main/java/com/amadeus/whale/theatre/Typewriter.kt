package com.amadeus.whale.theatre

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun Typewriter(text: String, finished: Boolean, modifier: Modifier = Modifier) {
  var shown by remember(text) { mutableIntStateOf(0) }
  LaunchedEffect(text, finished) {
    if (finished) { shown = text.length; return@LaunchedEffect }
    for (i in 1..text.length) { shown = i; delay(30) }
  }
  BasicText(text = text.take(shown), modifier = modifier)
}