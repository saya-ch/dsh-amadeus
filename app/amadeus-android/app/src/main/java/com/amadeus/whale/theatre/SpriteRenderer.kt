package com.amadeus.whale.theatre

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSprite

interface SpriteRenderer {
  @Composable fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier = Modifier)
}

object SpriteAssetMap {
  private val map = mapOf(
    AmadeusSprite.shy to "amadeus/whale-shy.webp",
    AmadeusSprite.think to "amadeus/whale-confused.webp",
    AmadeusSprite.tool to "amadeus/whale-serious.webp",
    AmadeusSprite.serious to "amadeus/whale-serious.webp",
    AmadeusSprite.wag to "amadeus/whale-cheerful.webp",
    AmadeusSprite.gray to "amadeus/whale-frightened.webp",
    AmadeusSprite.smile to "amadeus/whale-starry.webp",
    AmadeusSprite.talk to "amadeus/maid-left.webp",
  )
  fun asset(sprite: AmadeusSprite): String = map.getValue(sprite)
}

class StaticSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
      initialValue = 1.00f, targetValue = 1.02f,
      animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
      label = "breathScale",
    )
    AsyncImage(
      model = SpriteAssetMap.asset(sprite),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = modifier
        .fillMaxWidth()
        .heightIn(max = 480.dp) // 约屏高 60%
        .graphicsLayer { scaleX = scale; scaleY = scale },
    )
  }
}

class Live2DSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    Text("Live2D 待接入", modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center)
  }
}