package com.amadeus.whale.ui.theatre

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 解析 [[AMW:{...}]] 标签驱动 UI
 */
class TheatreViewModel {
  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState

  fun onMessage(raw: String) {
    // raw = "台词...\n[[AMW:{...}]]"
    val idx = raw.lastIndexOf("[[AMW:")
    if (idx == -1) {
      _uiState.value = _uiState.value.copy(dialog = raw, mood = AmadeusMood.idle, sprite = AmadeusSprite.smile)
      return
    }
    val clean = raw.substring(0, idx).trim()
    val tagJson = raw.substring(idx + 6, raw.lastIndexOf("]]"))
    // 简易解析，实际用 kotlinx.serialization
    try {
      val mood = Regex("\"mood\"\\s*:\\s*\"(\\w+)\"").find(tagJson)?.groupValues?.get(1) ?: "idle"
      val sprite = Regex("\"sprite\"\\s*:\\s*\"(\\w+)\"").find(tagJson)?.groupValues?.get(1) ?: "smile"
      _uiState.value = _uiState.value.copy(
        dialog = clean,
        mood = runCatching { AmadeusMood.valueOf(mood) }.getOrDefault(AmadeusMood.idle),
        sprite = runCatching { AmadeusSprite.valueOf(sprite) }.getOrDefault(AmadeusSprite.smile)
      )
      // TODO: voice/sfx/bgm 触发 SoundPool/ExoPlayer
    } catch (_: Exception) {
      _uiState.value = _uiState.value.copy(dialog = clean)
    }
  }
}
