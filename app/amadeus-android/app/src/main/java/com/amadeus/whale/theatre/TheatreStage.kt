package com.amadeus.whale.theatre

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.amadeus.whale.domain.model.AmadeusSprite
import com.amadeus.whale.theme.AmadeusCrossfade

/** 立绘映射：sprite → assets 文件（产品 1.6；AMW 只含 sprite）。 */
fun spriteAsset(sprite: AmadeusSprite): String = when (sprite) {
  AmadeusSprite.excited -> "whale-excited.webp"
  AmadeusSprite.happy -> "whale-cheerful.webp"
  AmadeusSprite.shy -> "whale-shy.webp"
  AmadeusSprite.thinking -> "whale-confused.webp"
  AmadeusSprite.exclaim -> "whale-exclaim.webp"
  AmadeusSprite.pout -> "whale-pout.webp"
  AmadeusSprite.deadpan -> "whale-deadpan.webp"
  AmadeusSprite.flustered -> "whale-flustered.webp"
  AmadeusSprite.normal -> "maid-normal.webp"
}

/**
 * 剧场舞台（架构 3.11）：背景 + 立绘，自然过渡（3.3 AmadeusCrossfade）。
 * 背景静态（调用方传用户所选）、立绘静态 + 切换 Crossfade（产品 1.6/1.7）。
 */
@Composable
fun TheatreStage(
  background: String,
  sprite: AmadeusSprite,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize()) {
    // 背景层（静态，切换 Crossfade，产品 1.7）
    AmadeusCrossfade(targetState = background, modifier = Modifier.fillMaxSize()) { name ->
      AsyncImage(
        model = "file:///android_asset/amadeus/$name.webp",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 立绘层（静态，切换 Crossfade，产品 1.6）
    AmadeusCrossfade(targetState = sprite, modifier = Modifier.fillMaxSize()) { s ->
      AsyncImage(
        model = "file:///android_asset/amadeus/${spriteAsset(s)}",
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxSize(),
      )
    }
  }
}
