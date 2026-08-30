package com.amadeus.whale.ui.theatre

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 立绘渲染抽象，支持静态切图与未来 Live2D 无缝切换
 */
enum class AmadeusMood { shy, think, tool, happy, sad, idle }
enum class AmadeusSprite { shy, think, tool, wag, gray, smile, talk }

interface SpriteRenderer {
  @Composable
  fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier = Modifier)
}

/**
 * MVP: 静态切图实现
 * Coil + Crossfade 加载 assets/sprites/whale_*.webp
 */
class StaticSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    // TODO: Coil AsyncImage with sprite mapping
    // val res = when(sprite) { shy -> R.drawable.whale_shy ... }
    // AsyncImage(model = res, contentDescription = null, modifier = modifier)
  }
}

/**
 * 未来: Live2D 实现占位
 * 依赖 Live2D Cubism SDK for Native 的 GLSurfaceView
 * mood -> motion 映射
 */
class Live2DSpriteRenderer(
  // private val model: Live2DModel // .model3.json
) : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    // TODO: AndroidView(factory = { context ->
    //   Live2DView(context).apply { loadModel("whale.model3.json") }
    // }) { view -> view.playMotion(mood.name) }
  }
}
