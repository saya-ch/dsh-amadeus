package com.amadeus.whale.theatre

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amadeus.whale.theme.LocalAmadeusColors

/**
 * 对话框（galgame 门面，产品 1.8）：名字牌 + 半透明深色圆角框 + 打字机 + 呼吸▼ + 点击反馈。
 * 一次文本事件 = 一次展示（架构 3.8：无翻页）。
 */
@Composable
fun DialogueBox(
  speaker: String,
  text: String,
  typing: Boolean,
  onClick: () -> Unit,
) {
  val colors = LocalAmadeusColors.current
  Column(modifier = Modifier.fillMaxWidth()) {
    // 名字牌（独立小牌子，产品 1.8）
    Box(
      modifier = Modifier
        .padding(start = 16.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(colors.namePlateBg)
        .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
      Text(text = speaker, color = colors.namePlateText, fontSize = 14.sp)
    }
    Spacer(Modifier.height(4.dp))
    // 对话框主体
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(colors.dialogueBox)
        .border(1.dp, colors.dialogueBorder, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Column {
        Typewriter(text = text, typing = typing, modifier = Modifier.fillMaxWidth())
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
          horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
          BreathingArrow(visible = !typing)
        }
      }
    }
  }
}

/** 呼吸▼（产品 1.8：提示有内容/等待中，不是翻页按钮）。 */
@Composable
private fun BreathingArrow(visible: Boolean) {
  if (!visible) return
  val colors = LocalAmadeusColors.current
  val transition = rememberInfiniteTransition(label = "breathing-arrow")
  val offsetY by transition.animateFloat(
    initialValue = 0f, targetValue = 4f,
    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
    label = "breathing-arrow-y",
  )
  Text(
    text = "▼",
    color = colors.breathingArrow,
    fontSize = 14.sp,
    modifier = Modifier.offset(y = offsetY.dp),
  )
}
