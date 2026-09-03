package com.amadeus.whale.theatre

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amadeus.whale.domain.ApprovalRepository
import com.amadeus.whale.domain.ChoiceRepository
import com.amadeus.whale.domain.SessionLog
import com.amadeus.whale.domain.SessionStateMachine
import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.AmadeusSprite
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
  val windowId: String? = null,
  val windowType: String? = null,
  val overlay: OverlayState? = null,
  val demoFinished: Boolean = false,
  /** 当前回显的用户消息（发送后蓝字显示在对话框，直到收到下一条鲸鱼娘回复）。 */
  val userText: String? = null,
)

/** 全屏浮动小字（幕后活动生动化，产品 1.20）：文案 + 唯一 id + 随机位置种子。 */
data class FlickNote(
  val id: Long,
  val text: String,
  val seedX: Float,   // 0..1 水平位置（避开对话框区，由 UI 取位）
  val seedY: Float,   // 0..1 垂直位置
  /** 回合结束渐隐标记：置 true 后 UI 播放淡出，随后 VM 移除。 */
  val fading: Boolean = false,
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

  // 全屏浮动小字队列（最近若干条，UI 渲染后各自计时消失）
  private val _flicks = MutableStateFlow<List<FlickNote>>(emptyList())
  val flicks: StateFlow<List<FlickNote>> = _flicks.asStateFlow()
  private var flickSeq = 0L

  /** 活动事件 → 浮动小字文案（英文短句，描述在干什么）。 */
  /** 事件流条目：detail 优先（工具参数/待办明细/审批理由），空则回退标题原文。 */
  private fun flickText(a: Activity): String {
    val detail = a.detail.trim().replace('\n', ' ')
    val t = a.title.trim()
    return when {
      // detail 够长（≥5）→ 用它；太短说明 detail 无信息量 → 回落标题
      detail.length >= 5 -> detail.take(90)
      else -> when (a.kind) {
        "tool" -> when {
          t.startsWith("调用") -> "${t.removePrefix("调用").trim()} …"
          t.endsWith("完成") -> "${t.removeSuffix("完成").trim()} ✓"
          t.endsWith("（失败）") -> "${t.removeSuffix("（失败）").trim()} ✗"
          else -> t
        }
        else -> t
      }
    }
  }

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
    log.appendUser(text) // 用户发言也进“记录”
    // 用户消息回显在对话框（蓝字），直到鲸鱼娘下一条回复到达
    _uiState.value = _uiState.value.copy(typing = false, dialogue = null, userText = text)
  }

  /** 回合结束：现存事件流条目全部进入渐隐（600ms 后移除，UI 同步淡出）。 */
  private fun clearFlicks() {
    val cur = _flicks.value
    if (cur.isEmpty()) return
    _flicks.value = cur.map { it.copy(fading = true) }
    viewModelScope.launch {
      kotlinx.coroutines.delay(650)
      _flicks.value = _flicks.value.filterNot { it.fading }
    }
  }

  /** 本地单行演出（不进 agent）：新会话默认开场等，仅门面。也进会话日志。 */
  fun showLocalLine(text: String, sprite: AmadeusSprite) {
    val d = Dialogue(text, AmadeusTag(sprite))
    log.append(StreamEvent.DialogueEvent(d))
    onDialogue(d)
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
    // demo 剧情也进会话日志（产品：demo 对话在“记录”侧栏可见）
    log.append(StreamEvent.DialogueEvent(d))
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
      is StreamEvent.TurnEnded -> {
        // agent 停笔：事件流渐隐清空；当前 dialogue 若正显示工作符号则去除（最终回答）
        clearFlicks()
        val cur = _uiState.value.dialogue
        if (cur != null && cur.working) {
          _uiState.value = _uiState.value.copy(dialogue = cur.copy(working = false))
        }
      }
      is StreamEvent.ActivityEvent -> {
        activities.add(event.activity)
        // 浮动小字：进队（最多 5 条同屏）；过短文案（<5 字符）不显示（信息量不足）
        val noteText = flickText(event.activity)
        if (noteText.length >= 5) {
          val note = FlickNote(
            id = ++flickSeq,
            text = noteText,
            seedX = kotlin.random.Random.nextFloat(),
            seedY = kotlin.random.Random.nextFloat(),
          )
          _flicks.value = (_flicks.value.filterNot { it.fading } + note).takeLast(5)
          viewModelScope.launch {
            kotlinx.coroutines.delay(9000)
            _flicks.value = _flicks.value.filterNot { it.id == note.id }
          }
        }
        _uiState.value = _uiState.value.copy(
          overlay = (_uiState.value.overlay as? OverlayState.EventLog)?.let {
            OverlayState.EventLog(activities.toList())
          } ?: _uiState.value.overlay,
        )
      }
    }
  }

  private fun onDialogue(d: Dialogue) {
    // 保留 overlay（选项/审批/小窗开着时新对话不该把它冲掉——避免提问卡片一闪就没）
    val prev = _uiState.value
    _uiState.value = TheatreUiState(
      dialogue = d,
      typing = true,
      windowId = d.tag.windowId.ifEmpty { null },
      windowType = d.tag.window.name.takeIf { it != "none" },
      overlay = prev.overlay,
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

  fun openHistory() = openOverlay(OverlayState.History(log.chatLines()))

  fun resolveChoice(choice: Choice, label: String) {
    android.util.Log.d("AMW", "vm resolveChoice ${choice.choiceId.take(12)} -> ${label.take(16)}")
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

  /** 关掉提问卡片且不回答：通知服务端取消该 choice（否则 ask_user_question 永远挂起堵住会话）。 */
  fun dismissChoice(choice: Choice) {
    android.util.Log.d("AMW", "vm dismissChoice ${choice.choiceId.take(12)}")
    viewModelScope.launch {
      choiceRepository?.cancel(choice.choiceId)
    }
    closeOverlay()
  }
}
