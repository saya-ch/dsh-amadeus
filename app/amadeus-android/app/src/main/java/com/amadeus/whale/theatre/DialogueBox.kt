package com.amadeus.whale.theatre

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theme.LocalAmadeusColors
import kotlin.math.max

/**
 * 对话框（galgame 门面，产品 1.8）。
 * 9/2 方案 A2：无边框无角饰；深蓝底从下到上指数渐透明（下方实、上方渐隐融入画面）。
 * 无名字；宽 = 屏宽；高 = 屏高 5/21；贴底。
 * 文本：鲸鱼娘话暖白打字机 / 用户消息回显亮蓝。
 *
 * 9/2 交互调整：
 * - 字号 20→18sp
 * - ▼ 只在演出态（[showArrow]）显示，且跟随最后一行文字末尾（不再是固定右下角）
 */
private val DialogueBase = Color(0xFF0B1830)    // 底部实色（深蓝）
private val UserBlue = Color(0xFF9DC9FF)        // 用户话亮蓝

@Composable
fun DialogueBox(
  text: String,
  typing: Boolean,
  userText: String?,
  showArrow: Boolean,
  showWorking: Boolean = false,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onTypingFinished: (() -> Unit)? = null,
) {
  val isUser = userText != null
  val screenH = LocalConfiguration.current.screenHeightDp.dp
  val boxH = screenH * 5f / 21f
  // 文字 layout 结果：句末 ▼ 定位用（最后一行右缘）
  var lastLineEnd by remember { mutableStateOf<Pair<Float, Float>?>(null) }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(boxH)
      .clickable(onClick = onClick),
  ) {
    // 深蓝底：仅顶部 15% 线性变透明，其余全实（alpha 0.8）
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colorStops = arrayOf(
              0f to DialogueBase.copy(alpha = 0f),   // 顶：全透明
              0.15f to DialogueBase.copy(alpha = 0.8f), // 顶部 15% 内到实
              1f to DialogueBase.copy(alpha = 0.8f), // 其余全实
            ),
          ),
        ),
    )
    // 文本（避开顶部 15% 透明区，从 17% 处开始）
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(start = 26.dp, end = 26.dp, top = boxH * 0.17f, bottom = 14.dp),
    ) {
      if (isUser) {
        Text(
          text = userText.orEmpty(),
          color = UserBlue,
          fontFamily = com.amadeus.whale.theme.AmadeusFontFamily,
          fontSize = 18.sp,
          lineHeight = 30.sp,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        Typewriter(
          text = text,
          typing = typing,
          modifier = Modifier.fillMaxWidth(),
          textColor = Color(0xFFFFF5E6),
          fontSize = 18.sp,
          onFinished = onTypingFinished,
          onTextLayout = { layout: TextLayoutResult ->
            val last = layout.lineCount - 1
            if (last >= 0) {
              val l = layout.getLineRight(last)
              val top = layout.getLineTop(last)
              val bottom = layout.getLineBottom(last)
              lastLineEnd = l to (top + bottom) / 2f
            }
          },
        )
      }
    }
    // 句末符号：调用方控制——演出态 ▼（showArrow）或工作中旁白动画（showWorking，真实模式任务中）
    if ((showArrow || showWorking) && !isUser) {
      val end = lastLineEnd
      val density = LocalDensity.current
      Box(
        modifier = Modifier
          .padding(start = 26.dp, top = boxH * 0.17f)
          .offset(
            x = with(density) { (end?.first ?: 0f).toDp() },
            y = with(density) { (end?.second ?: 0f).toDp() } - 8.dp,
          ),
      ) {
        if (showWorking) WorkingMark() else BreathingArrow()
      }
    }
  }
}

/** 呼吸▼（演出提示）；跟随文字句末，打字未满时不显示（避免闪烁）？——不，打字中也跟（字末即箭头）。 */
@Composable
private fun BreathingArrow(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "breathing-arrow")
  val offsetY by transition.animateFloat(
    initialValue = 0f, targetValue = 3f,
    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
    label = "breathing-arrow-y",
  )
  Text(
    text = "▼",
    color = Color(0xFFFFF5E6).copy(alpha = 0.7f),
    fontSize = 13.sp,
    modifier = modifier.offset(y = offsetY.dp),
  )
}

/** 工作中旁白句末标记：三点循环涨落（……又继续干活的律动），暖白小字。 */
@Composable
private fun WorkingMark(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "working-mark")
  val phase by transition.animateFloat(
    initialValue = 0f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
    label = "working-phase",
  )
  val dots = when {
    phase < 0.33f -> "·"
    phase < 0.66f -> "··"
    else -> "···"
  }
  Text(
    text = dots,
    color = Color(0xFFFFD98A).copy(alpha = 0.85f),
    fontSize = 15.sp,
    modifier = modifier,
  )
}
