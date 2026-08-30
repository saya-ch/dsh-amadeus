package com.amadeus.whale.ui.theatre

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 立绘渲染抽象，支持静态切图与未来 Live2D 无缝切换
 * 公开素材来源: JAdpp/dsh-whale-galgame assets/default (MIT, 已下载到 app/assets)
 * 视觉检查: 8 张 whale-* + maid-left + pet-spritesheet 均为有效 RIFF/WEBP
 */
enum class AmadeusMood { shy, think, tool, happy, sad, idle }
enum class AmadeusSprite { shy, think, tool, wag, gray, smile, talk }

interface SpriteRenderer {
  @Composable
  fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier = Modifier)
}

/**
 * MVP: 静态切图实现 - 映射到真实公开素材
 * shy -> whale-shy.webp, think -> whale-confused.webp, tool -> whale-serious.webp,
 * wag -> whale-cheerful.webp, gray -> whale-frightened.webp, smile -> whale-starry.webp,
 * talk -> maid-left.webp
 */
class StaticSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    // val res = when(sprite) {
    //   shy -> R.drawable.whale_shy
    //   think -> R.drawable.whale_confused
    //   tool -> R.drawable.whale_serious
    //   wag -> R.drawable.whale_cheerful
    //   gray -> R.drawable.whale_frightened
    //   smile -> R.drawable.whale_starry
    //   talk -> R.drawable.maid_left
    // }
    // AsyncImage(model = res, contentDescription = null, modifier = modifier)
  }
}

/**
 * 未来: Live2D 实现占位
 * 依赖 Live2D Cubism SDK for Native 的 GLSurfaceView
 */
class Live2DSpriteRenderer(
  // private val model: Live2DModel // .model3.json
) : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    // AndroidView(factory = { context -> Live2DView(context).apply { loadModel("whale.model3.json") } })
  }
}
