package com.amadeus.whale.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theme.LocalAmadeusColors

/** 标题画面：logo（用户生成）+ 标题，淡入；兼作启动决策加载态（架构 3.13）。 */
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
        // TODO: 用户用 GPT image2 生成的鲸鱼娘 logo（产品 1.11）
        Text(
          text = "🐳",
          fontSize = 96.sp,
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
