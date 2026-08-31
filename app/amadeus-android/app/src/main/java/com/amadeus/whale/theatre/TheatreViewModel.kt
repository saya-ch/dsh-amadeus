package com.amadeus.whale.theatre

import androidx.compose.runtime.Stable
import com.amadeus.whale.feed.MessageFeed
import com.amadeus.whale.feed.RealFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSprite
import com.amadeus.whale.model.AmadeusWindow
import com.amadeus.whale.network.StreamEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable
data class TheatreUiState(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val dialog: String = "",
  val speaker: String = "鲸鱼娘",
  val windowId: String? = null,
  val windowType: AmadeusWindow? = null,
  val background: String = "palace-night",
  val typingFinished: Boolean = false,
  val choice: ChoiceUi? = null,
)

@Stable
data class ChoiceUi(val choiceId: String, val question: String, val options: List<String>)

class TheatreViewModel(
  private val feed: MessageFeed,
  private val backgroundResolver: (AmadeusMood) -> String = { "palace-night" },
) {
  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState
  private val _queue = MutableStateFlow<List<AmadeusSegment>>(emptyList())
  private val _index = MutableStateFlow(0)
  private var pendingChoice: ChoiceUi? = null

  val backgroundResolverFor: (AmadeusMood) -> String = backgroundResolver

  suspend fun load() {
    val segs = feed.initial()
    _queue.value = segs
    _index.value = 0
    _uiState.value = segs.firstOrNull()?.let { toUi(it) } ?: TheatreUiState()
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
    _uiState.value = toUi(q.last()).copy(typingFinished = true)
  }

  fun hasNext(): Boolean = _index.value + 1 < _queue.value.size

  fun enqueue(segment: AmadeusSegment) {
    val hadCurrent = hasCurrent()
    _queue.value = _queue.value + segment
    if (!hadCurrent) _uiState.value = toUi(segment)
  }

  fun showChoice(event: StreamEvent.Choice) {
    pendingChoice = ChoiceUi(event.choiceId, event.question, event.options.map { it.label })
    _uiState.value = _uiState.value.copy(choice = pendingChoice)
  }

  fun dismissChoice() {
    pendingChoice = null
    _uiState.value = _uiState.value.copy(choice = null)
  }

  fun markIdle() {
    _uiState.value = _uiState.value.copy(mood = AmadeusMood.idle, typingFinished = true)
  }

  suspend fun sendToFeed(text: String) { (feed as? RealFeed)?.send(text) }

  private fun hasCurrent(): Boolean = _index.value < _queue.value.size

  private fun toUi(seg: AmadeusSegment) = TheatreUiState(
    mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
    windowId = seg.windowId,
    windowType = seg.tag.window.takeIf { it != AmadeusWindow.none },
    background = backgroundResolver(seg.tag.mood),
  )

  private fun advance(): Boolean {
    val q = _queue.value
    val next = _index.value + 1
    if (next >= q.size) return false
    _index.value = next
    _uiState.value = toUi(q[next])
    return true
  }
}