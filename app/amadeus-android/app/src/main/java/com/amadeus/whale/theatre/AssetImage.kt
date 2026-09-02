package com.amadeus.whale.theatre

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * 加载 assets/amadeus/ 下的素材（立绘/背景/装饰）。
 * 注意：不内部包 Box——align 等 BoxScope 修饰符需在调用处通过父 Box 提供。
 */
@Composable
fun AssetImage(
  name: String,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.FillBounds,
) {
  AsyncImage(
    model = "file:///android_asset/amadeus/$name.webp",
    contentDescription = null,
    contentScale = contentScale,
    modifier = modifier,
  )
}
