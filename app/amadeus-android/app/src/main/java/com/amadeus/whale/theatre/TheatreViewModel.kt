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
  val sfx: String = "none",
  val bgm: String = "none",
  val windowId: String? = null,
  val windowType: AmadeusWindow? = null,
  val background: String = "palace-night",
  val typingFinished: Boolean = false,
  val choice: ChoiceUi? = null,
)

/**
 * Side-effect callback fired on every segment change. The host (`AppRoot`)
 * wires this to `AmbientSound` so the per-segment `sfx` / `bgm` labels
 * actually trigger playback; the view-model itself stays audio-agnostic and
 * remains testable without an Android context.
 */
fun interface TheatreSoundListener {
  fun onSegment(segment: AmadeusSegment)
}

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
  var soundListener: TheatreSoundListener? = null

  val backgroundResolverFor: (AmadeusMood) -> String = backgroundResolver

  suspend fun load() {
    val segs = feed.initial()
    _queue.value = segs
    _index.value = 0
    val first = segs.firstOrNull()
    if (first != null) {
      val ui = toUi(first)
      _uiState.value = ui
      soundListener?.onSegment(first)
    } else {
      _uiState.value = TheatreUiState()
    }
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
    val last = q.last()
    val ui = toUi(last).copy(typingFinished = true)
    _uiState.value = ui
    soundListener?.onSegment(last)
  }

  fun hasNext(): Boolean = _index.value + 1 < _queue.value.size

  fun enqueue(segment: AmadeusSegment) {
    val hadCurrent = hasCurrent()
    _queue.value = _queue.value + segment
    if (!hadCurrent) {
      val ui = toUi(segment)
      _uiState.value = ui
      soundListener?.onSegment(segment)
    }
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
    sfx = seg.tag.sfx, bgm = seg.tag.bgm,
    windowId = seg.windowId,
    windowType = seg.tag.window.takeIf { it != AmadeusWindow.none },
    background = backgroundResolver(seg.tag.mood),
  )

  private fun advance(): Boolean {
    val q = _queue.value
    val next = _index.value + 1
    if (next >= q.size) return false
    _index.value = next
    val seg = q[next]
    _uiState.value = toUi(seg)
    soundListener?.onSegment(seg)
    return true
  }
}