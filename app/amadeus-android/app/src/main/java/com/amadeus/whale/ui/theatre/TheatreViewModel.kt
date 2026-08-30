package com.amadeus.whale.ui.theatre

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 多句分页 + 点一下下一句
 * 模型输出: 句1\n[[AMW:...]]\n句2\n[[AMW:...]] -> 队列，点一下切下一句
 */
class TheatreViewModel {
  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState
  private val _queue = MutableStateFlow<List<TheatreUiState>>(emptyList())
  val queue: StateFlow<List<TheatreUiState>> = _queue
  private val _index = MutableStateFlow(0)
  val index: StateFlow<Int> = _index

  fun onMessage(raw: String) {
    val segments = parseSegments(raw)
    _queue.value = segments
    _index.value = 0
    _uiState.value = segments.firstOrNull() ?: TheatreUiState(dialog = raw)
  }

  fun onTapNext(): Boolean {
    val q = _queue.value
    val i = _index.value + 1
    if (i >= q.size) return false
    _index.value = i
    _uiState.value = q[i]
    return true
  }

  fun hasNext(): Boolean = _index.value + 1 < _queue.value.size

  private fun parseSegments(raw: String): List<TheatreUiState> {
    val re = Regex("\\[\\[AMW:\\s*(\\{[\\s\\S]*?\\})\\s*\\]\\]")
    val segs = mutableListOf<TheatreUiState>()
    var last = 0
    var m = re.find(raw, last)
    while (m != null) {
      val clean = raw.substring(last, m.range.first).trim()
      val tagJson = m.groupValues[1]
      val mood = Regex("\"mood\"\\s*:\\s*\"(\\w+)\"").find(tagJson)?.groupValues?.get(1) ?: "idle"
      val sprite = Regex("\"sprite\"\\s*:\\s*\"(\\w+)\"").find(tagJson)?.groupValues?.get(1) ?: "smile"
      if (clean.isNotEmpty()) {
        segs.add(TheatreUiState(dialog = clean,
          mood = runCatching { AmadeusMood.valueOf(mood) }.getOrDefault(AmadeusMood.idle),
          sprite = runCatching { AmadeusSprite.valueOf(sprite) }.getOrDefault(AmadeusSprite.smile)))
      }
      last = m.range.last + 1
      while (last < raw.length && (raw[last] == '\n' || raw[last] == '\r')) last++
      m = re.find(raw, last)
    }
    val tail = raw.substring(last).trim()
    if (tail.isNotEmpty()) segs.add(TheatreUiState(dialog = tail))
    if (segs.isEmpty() && raw.trim().isNotEmpty()) segs.add(TheatreUiState(dialog = raw.trim()))
    return segs
  }
}
