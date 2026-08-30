package com.amadeus.whale.theatre

import androidx.compose.runtime.Stable
import com.amadeus.whale.feed.MessageFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSprite
import com.amadeus.whale.model.AmadeusSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable
data class TheatreUiState(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val dialog: String = "",
  val speaker: String = "鲸鱼娘",
  val windowId: String? = null,
  val background: String = "palace-night",
  val typingFinished: Boolean = false,
)

class TheatreViewModel(
  private val feed: MessageFeed,
  private val backgroundResolver: (AmadeusMood) -> String = { "palace-night" },
) {
  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState
  private val _queue = MutableStateFlow<List<AmadeusSegment>>(emptyList())
  private val _index = MutableStateFlow(0)

  suspend fun load() {
    val segs = feed.initial()
    _queue.value = segs
    _index.value = 0
    _uiState.value = segs.firstOrNull()?.let { seg ->
      TheatreUiState(
        mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
        windowId = seg.windowId, background = backgroundResolver(seg.tag.mood),
      )
    } ?: TheatreUiState()
  }

  fun onTap(): Boolean {
    if (!_uiState.value.typingFinished) {
      _uiState.value = _uiState.value.copy(typingFinished = true)
      return true
    }
    return advance()
  }

  fun skipToEnd() {
    val q = _queue.value
    if (q.isEmpty()) return
    _index.value = q.size - 1
    val seg = q.last()
    _uiState.value = TheatreUiState(
      mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
      windowId = seg.windowId, background = backgroundResolver(seg.tag.mood), typingFinished = true,
    )
  }

  fun hasNext(): Boolean = _index.value + 1 < _queue.value.size

  private fun advance(): Boolean {
    val q = _queue.value
    val next = _index.value + 1
    if (next >= q.size) return false
    _index.value = next
    val seg = q[next]
    _uiState.value = TheatreUiState(
      mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
      windowId = seg.windowId, background = backgroundResolver(seg.tag.mood),
    )
    return true
  }
}