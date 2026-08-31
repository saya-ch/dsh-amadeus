package com.amadeus.whale.theatre

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.amadeus.whale.model.AmadeusMood
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun MoodEffects(mood: AmadeusMood, modifier: Modifier = Modifier) {
  if (mood == AmadeusMood.tool || mood == AmadeusMood.think) return
  val particles = remember { List(12) { Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 80f + 20f) } }
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(mood) { while (true) { delay(50); tick++ } }
  Canvas(modifier = modifier) {
    particles.forEach { p ->
      val drift = sin((tick * 0.05f) + p.x * 6f) * 8f
      val y = when (mood) {
        AmadeusMood.shy -> (p.y - (tick % 200) / 200f).mod(1f) * size.height // 上浮气泡
        AmadeusMood.sad -> p.y * size.height + (tick % 300) * 0.2f // 雨丝下坠
        else -> (p.y + (tick % 200) / 200f).mod(1f) * size.height // 海面光斑漂移
      }
      drawCircle(
        color = if (mood == AmadeusMood.sad) Color(0x88ADD8E6) else Color(0x44FFFFFF),
        radius = p.r,
        center = Offset((p.x * size.width + drift).mod(size.width), y),
      )
    }
  }
}
private data class Particle(val x: Float, val y: Float, val r: Float)