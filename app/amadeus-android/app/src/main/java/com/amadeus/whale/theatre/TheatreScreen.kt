package com.amadeus.whale.theatre

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusWindow

@Composable
fun TheatreScreen(
  viewModel: TheatreViewModel,
  renderer: SpriteRenderer = StaticSpriteRenderer(),
  backgroundResolver: (AmadeusMood) -> String = { "palace-night" },
  onOpenSettings: () -> Unit = {},
  onOpenWindow: (windowId: String, type: AmadeusWindow) -> Unit = { _, _ -> },
  onOpenHistory: (() -> Unit)? = null,
  inputBar: @Composable () -> Unit = {},
) {
  val state by viewModel.uiState.collectAsState()
  val bg by remember(state.background) { mutableStateOf(state.background) }

  Box(modifier = Modifier.fillMaxSize()) {
    // 背景层（Crossfade 1s）
    Crossfade(targetState = bg, animationSpec = androidx.compose.animation.core.tween(1000), label = "bg") { name ->
      AsyncImage(
        model = "file:///android_asset/amadeus/$name.webp",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 心情点缀层（背景之上、立绘之下；tool/think 不渲染）
    MoodEffects(mood = state.mood, modifier = Modifier.fillMaxSize())
    // 立绘层（换 sprite 滑动入场）
    AnimatedContent(
      targetState = state.sprite,
      transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith fadeOut() },
      label = "sprite",
    ) { sprite ->
      renderer.Render(mood = state.mood, sprite = sprite, modifier = Modifier.align(Alignment.BottomCenter))
    }
    // 底部：输入栏插槽（真实模式用，demo 传空 lambda 不渲染）+ 对话框层
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      inputBar()
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(20.dp))
          .background(Color(0xCC000000))
          .clickable { viewModel.onTap() }
          .padding(horizontal = 20.dp, vertical = 16.dp),
      ) {
        Text(text = state.speaker, color = Color(0xFFFFD6A5), fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Typewriter(text = state.dialog, finished = state.typingFinished, modifier = Modifier.fillMaxWidth())
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (viewModel.hasNext()) Text(text = "▼", color = Color(0x88FFFFFF), fontSize = 14.sp)
        }
      }
    }
    // 右上角：快进（demo 可快进到底）+ 齿轮
    Row(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { viewModel.skipToEnd() }) { Text(text = "快进", color = Color.White) }
      IconButton(onClick = onOpenSettings) {
        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
      }
    }
    // 左上角历史（真实模式传入非空才显示）
    if (onOpenHistory != null) {
      TextButton(
        onClick = onOpenHistory,
        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
      ) { Text(text = "历史", color = Color.White) }
    }
  }
}