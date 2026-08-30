package com.amadeus.whale.ui.theatre

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Galgame 剧场主舞台：全屏背景 + 立绘 + 底部对话框
 */
@Composable
fun TheatreScreen(
  viewModel: TheatreViewModel,
  renderer: SpriteRenderer = StaticSpriteRenderer()
) {
  val uiState by viewModel.uiState.collectAsState()
  Box(modifier = Modifier.fillMaxSize()) {
    // 背景层
    // AsyncImage(model = uiState.background, ...)
    // 立绘层
    AnimatedContent(targetState = uiState.sprite, label = "sprite") { sprite ->
      renderer.Render(mood = uiState.mood, sprite = sprite, modifier = Modifier.fillMaxSize())
    }
    // 对话框层
    Column(
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
    ) {
      Text(text = uiState.speaker) // "鲸鱼娘"
      Text(text = uiState.dialog) // 打字机效果
      // 思考气泡 / 道具卡片 根据 mood 展开
    }
  }
}

data class TheatreUiState(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val speaker: String = "鲸鱼娘",
  val dialog: String = "呜... 第一次在月夜的礁石边遇见你...",
  val background: String = "reef_night"
)
