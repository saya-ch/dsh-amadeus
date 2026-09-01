package com.amadeus.whale.theatre

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amadeus.whale.domain.ApprovalRepository
import com.amadeus.whale.domain.ChoiceRepository
import com.amadeus.whale.domain.SessionLog
import com.amadeus.whale.domain.SessionStateMachine
import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.AmadeusTag
import com.amadeus.whale.domain.model.ApprovalRequest
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.Dialogue
import com.amadeus.whale.domain.model.StreamEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 剧场 UI 状态（架构 3.11：状态提升，组件纯展示）。 */
data class TheatreUiState(
  val dialogue: Dialogue? = null,
  val typing: Boolean = false,
  val speaker: String = "鲸鱼娘",
  val background: String = "bg-claude-writing-study",
  val windowId: String? = null,
  val windowType: String? = null,
  val overlay: OverlayState? = null,
  val demoFinished: Boolean = false,
)

/**
 * 剧场 ViewModel：接 SessionLog/状态机 + feed，向 UI 暴露状态。
 * demo 模式：本地剧本驱动；真实模式：SSE 事件驱动。
 */
class TheatreViewModel(
  private val log: SessionLog = SessionLog(),
  private val stateMachine: SessionStateMachine = SessionStateMachine(log),
  private val choiceRepository: ChoiceRepository? = null,
  private val approvalRepository: ApprovalRepository? = null,
  private val haptics: com.amadeus.whale.platform.Haptics? = null,
  private var hapticsEnabled: Boolean = true,
) : ViewModel() {

  fun setHapticsEnabled(enabled: Boolean) { this.hapticsEnabled = enabled }

  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState.asStateFlow()

  private var demoIndex = 0
  private var demoScript: List<Dialogue> = emptyList()
  private var sender: ((String) -> Unit)? = null
  private val activities = mutableListOf<Activity>()

  /** 真实模式：注入发送器（发消息给 agent）。 */
  fun setSender(sender: (String) -> Unit) { this.sender = sender }

  /** 发送消息（真实模式）。 */
  fun send(text: String) {
    sender?.invoke(text)
    if (hapticsEnabled) haptics?.sendVibration()  // 产品 1.11：发送消息时短震（可关）
    stateMachine.send()
    _uiState.value = _uiState.value.copy(typing = false, dialogue = null)
  }

  /** demo 模式：加载本地剧本。 */
  fun loadDemo(script: List<Dialogue>, background: String) {
    demoScript = script
    demoIndex = 0
    stateMachine.clear()
    showDemoDialogue(0)
  }

  private fun showDemoDialogue(index: Int) {
    if (index >= demoScript.size) {
      // demo 播完：标记结束（TheatreScreen 观察后回调 onDemoFinished）
      _uiState.value = _uiState.value.copy(demoFinished = true)
      return
    }
    demoIndex = index
    val d = demoScript[index]
    onDialogue(d)
  }

  /** 真实模式：喂 SSE 事件。 */
  fun onStreamEvent(event: StreamEvent) {
    stateMachine.onEvent(event)
    when (event) {
      is StreamEvent.DialogueEvent -> onDialogue(event.dialogue)
      is StreamEvent.ChoiceEvent -> {
        _uiState.value = _uiState.value.copy(typing = false, overlay = OverlayState.ChoicePrompt(event.choice))
      }
      is StreamEvent.ApprovalEvent -> {
        // 方案 B：审批请求弹卡片（批准/拒绝）
        _uiState.value = _uiState.value.copy(typing = false, overlay = OverlayState.ApprovalPrompt(event.approval))
      }
      is StreamEvent.Ended -> _uiState.value = _uiState.value.copy(typing = false)
      is StreamEvent.ActivityEvent -> {
        activities.add(event.activity)
        _uiState.value = _uiState.value.copy(
          overlay = (_uiState.value.overlay as? OverlayState.EventLog)?.let {
            OverlayState.EventLog(activities.toList())
          } ?: _uiState.value.overlay,
        )
      }
    }
  }

  private fun onDialogue(d: Dialogue) {
    _uiState.value = TheatreUiState(
      dialogue = d,
      typing = true,
      background = resolveBackground(d.tag),
      windowId = d.tag.windowId.ifEmpty { null },
      windowType = d.tag.window.name.takeIf { it != "none" },
    )
  }

  /** 点击对话框：打断打字（架构 3.8）。 */
  fun onTap() {
    if (_uiState.value.typing) {
      stateMachine.tapToComplete()
      _uiState.value = _uiState.value.copy(typing = false)
    } else {
      // demo 模式：下一句；真实模式：无翻页（等 agent）
      if (demoScript.isNotEmpty()) showDemoDialogue(demoIndex + 1)
    }
  }

  /** 快进（demo）：直接到结尾。 */
  fun skipToEnd() {
    if (demoScript.isEmpty()) return
    val last = demoScript.last()
    _uiState.value = TheatreUiState(
      dialogue = last,
      typing = false,
      background = resolveBackground(last.tag),
    )
  }

  // ---- 覆盖层 ----

  fun openOverlay(overlay: OverlayState) {
    _uiState.value = _uiState.value.copy(overlay = overlay)
  }

  fun closeOverlay() {
    _uiState.value = _uiState.value.copy(overlay = null)
  }

  fun openEventLog() = openOverlay(OverlayState.EventLog(activities.toList()))

  fun openHistory() = openOverlay(OverlayState.History(log.dialogueEntries()))

  fun resolveChoice(choice: Choice, label: String) {
    viewModelScope.launch {
      choiceRepository?.resolve(choice.choiceId, label)
    }
    closeOverlay()
  }

  /** 审批决定（方案 B：批准/拒绝）。 */
  fun decideApproval(approval: ApprovalRequest, allowed: Boolean) {
    viewModelScope.launch {
      approvalRepository?.decide(approval.approvalId, allowed)
    }
    closeOverlay()
  }

  private fun resolveBackground(tag: AmadeusTag): String = when (tag.mood.name) {
    "think", "tool" -> "bg-gpt-collaboration-workshop"
    else -> "bg-claude-writing-study"
  }
}
