package com.amadeus.whale.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 过渡语言统一 spec（产品 1.15 / 架构 3.3）。 */
object AmadeusMotion {
  /** 覆盖层：边缘滑入 + 淡入。 */
  const val OverlayDurationMs = 250
  /** 内容切换：短 Crossfade + 轻微缩放。 */
  const val ContentDurationMs = 300
  /** 对话框打字机等特殊场景的基准。 */
  const val TypewriterBaseMs = 45

  val OverlayEasing = FastOutSlowInEasing
  val ContentEasing = FastOutSlowInEasing
}

/**
 * 覆盖层原语：统一"从边缘滑入 + 淡入"（250ms）。
 * [from] 决定从哪个边缘滑入（bottom=设置/报告，end=侧栏，start=左侧栏）。
 */
@Composable
fun AmadeusOverlay(
  visible: Boolean,
  modifier: Modifier = Modifier,
  from: Alignment = Alignment.BottomCenter,
  content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) {
  val enter = when (from) {
    Alignment.BottomCenter -> slideInVertically(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { it } + fadeIn(tween(AmadeusMotion.OverlayDurationMs))
    Alignment.CenterEnd -> slideInHorizontally(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { it } + fadeIn(tween(AmadeusMotion.OverlayDurationMs))
    Alignment.CenterStart -> slideInHorizontally(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { -it } + fadeIn(tween(AmadeusMotion.OverlayDurationMs))
    else -> fadeIn(tween(AmadeusMotion.OverlayDurationMs))
  }
  val exit = when (from) {
    Alignment.BottomCenter -> slideOutVertically(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { it } + fadeOut(tween(AmadeusMotion.OverlayDurationMs))
    Alignment.CenterEnd -> slideOutHorizontally(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { it } + fadeOut(tween(AmadeusMotion.OverlayDurationMs))
    Alignment.CenterStart -> slideOutHorizontally(tween(AmadeusMotion.OverlayDurationMs, easing = AmadeusMotion.OverlayEasing)) { -it } + fadeOut(tween(AmadeusMotion.OverlayDurationMs))
    else -> fadeOut(tween(AmadeusMotion.OverlayDurationMs))
  }
  androidx.compose.animation.AnimatedVisibility(
    visible = visible,
    enter = enter,
    exit = exit,
    modifier = modifier,
    content = content,
  )
}

/**
 * 内容切换原语：统一"短 Crossfade + 轻微缩放"（300ms）。
 * 用于立绘/背景等内容切换（产品 1.15）。
 */
@Composable
fun <T> AmadeusCrossfade(
  targetState: T,
  modifier: Modifier = Modifier,
  content: @Composable (T) -> Unit,
) {
  Crossfade(
    targetState = targetState,
    animationSpec = tween(AmadeusMotion.ContentDurationMs, easing = AmadeusMotion.ContentEasing),
    modifier = modifier,
    label = "amadeus-crossfade",
    content = content,
  )
}

/** 将主题 id 映射到 Material3 colorScheme + AmadeusColors，双通道注入（架构 3.2）。 */
@Composable
fun AmadeusTheme(
  themeId: AmadeusThemeId,
  content: @Composable () -> Unit,
) {
  val colors = when (themeId) {
    AmadeusThemeId.WARM_HEALING -> WarmHealingAmadeusColors
    AmadeusThemeId.DARK -> DarkAmadeusColors
  }
  val materialScheme = if (themeId == AmadeusThemeId.DARK) {
    darkColorScheme(
      primary = colors.accent,
      background = colors.screenBackground,
      surface = colors.cardBackground,
      onPrimary = Color.White,
      onBackground = colors.primaryText,
      onSurface = colors.primaryText,
    )
  } else {
    lightColorScheme(
      primary = colors.accent,
      background = colors.screenBackground,
      surface = colors.cardBackground,
      onPrimary = Color.White,
      onBackground = colors.primaryText,
      onSurface = colors.primaryText,
    )
  }
  CompositionLocalProvider(LocalAmadeusColors provides colors) {
    MaterialTheme(
      colorScheme = materialScheme,
      content = content,
    )
  }
}
