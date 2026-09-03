package com.amadeus.whale.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theme.LocalAmadeusColors
import com.amadeus.whale.theatre.AssetImage

/** 标题画面：鲸鱼娘立绘 + 标题，淡入；兼作启动决策加载态（架构 3.13）。 */
@Composable
fun TitleScreen(
  logoVisible: Boolean = true,
  subtitle: String = "手机上的鲸鱼娘",
  onFinish: () -> Unit = {},
) {
  val colors = LocalAmadeusColors.current
  val alpha = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    alpha.animateTo(1f, tween(800))
    kotlinx.coroutines.delay(400)
    onFinish()
  }
  Box(
    modifier = Modifier.fillMaxSize().background(colors.screenBackground),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.alpha(alpha.value).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (logoVisible) {
        // 立绘即 logo（产品 1.11 前先用 maid-normal 站姿立绘，不再用 emoji）
        AssetImage(
          name = "maid-normal",
          modifier = Modifier.heightIn(max = 420.dp),
          contentScale = ContentScale.Fit,
        )
      }
      Text(
        text = "Amadeus",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = colors.primaryText,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.secondaryText,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
