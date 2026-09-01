package com.amadeus.whale.domain

import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志条目：演出段 或 幕后活动（架构 3.9，append-only）。 */
sealed class LogEntry {
  data class DialogueEntry(val dialogue: Dialogue) : LogEntry()
  data class ActivityEntry(val activity: Activity) : LogEntry()
  data class ChoiceEntry(val choice: Choice) : LogEntry()
}

/**
 * 会话日志（架构 3.9/3.10）：事实记录，真相源。
 * 事件流侧栏 = 全量视图；对话记录侧栏 = 只 Dialogue 的过滤视图。
 */
class SessionLog {
  private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
  val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

  fun append(event: StreamEvent) {
    val entry = when (event) {
      is StreamEvent.DialogueEvent -> LogEntry.DialogueEntry(event.dialogue)
      is StreamEvent.ActivityEvent -> LogEntry.ActivityEntry(event.activity)
      is StreamEvent.ChoiceEvent -> LogEntry.ChoiceEntry(event.choice)
      is StreamEvent.Ended -> return // 结束不是日志条目
    }
    _entries.value = _entries.value + entry
  }

  fun appendAll(events: List<StreamEvent>) = events.forEach { append(it) }

  /** 演出文本（对话记录侧栏，产品 1.9：只显示参加演出的对话）。 */
  fun dialogueEntries(): List<Dialogue> = _entries.value.filterIsInstance<LogEntry.DialogueEntry>().map { it.dialogue }

  /** 全量（事件流侧栏）。 */
  fun allEntries(): List<LogEntry> = _entries.value

  fun clear() { _entries.value = emptyList() }
}

/** 会话状态（架构 3.10：派生状态，表现源）。 */
enum class SessionPhase { IDLE, WORKING, WAITING_CHOICE, ERROR }

data class SessionState(
  val phase: SessionPhase = SessionPhase.IDLE,
  val currentDialogue: Dialogue? = null,
  val typing: Boolean = false,
  val choice: Choice? = null,
)

/**
 * 会话状态机（架构 3.10）：日志为真相源，状态为派生。
 * 订阅日志（append 时更新状态），不维护两份独立状态。
 */
class SessionStateMachine(private val log: SessionLog) {
  private val _state = MutableStateFlow(SessionState())
  val state: StateFlow<SessionState> = _state.asStateFlow()

  fun onEvent(event: StreamEvent) {
    log.append(event)
    when (event) {
      is StreamEvent.DialogueEvent -> _state.value = SessionState(
        phase = SessionPhase.WORKING,
        currentDialogue = event.dialogue,
        typing = true,
        choice = null,
      )
      is StreamEvent.ActivityEvent -> {
        // 幕后活动不改当前演出，仅日志；若在等待，保持等待
        _state.value = _state.value.copy(phase = SessionPhase.WORKING)
      }
      is StreamEvent.ChoiceEvent -> _state.value = SessionState(
        phase = SessionPhase.WAITING_CHOICE,
        currentDialogue = null,
        typing = false,
        choice = event.choice,
      )
      is StreamEvent.Ended -> _state.value = _state.value.copy(
        phase = SessionPhase.IDLE,
        typing = false,
      )
    }
  }

  /** 点击对话框：打断打字（立即打满），架构 3.8。 */
  fun tapToComplete() {
    val s = _state.value
    if (s.typing) _state.value = s.copy(typing = false)
  }

  fun send() {
    _state.value = SessionState(phase = SessionPhase.WORKING)
  }

  fun error(message: String) {
    _state.value = SessionState(phase = SessionPhase.ERROR)
  }

  fun clear() {
    log.clear()
    _state.value = SessionState()
  }
}
