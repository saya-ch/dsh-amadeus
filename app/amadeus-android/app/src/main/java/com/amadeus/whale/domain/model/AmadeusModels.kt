package com.amadeus.whale.domain.model

/** sprite（立绘形象），AMW 标签协议（产品 1.5）。当前 AMW 仅携带 sprite 字段。 */
enum class AmadeusSprite { excited, happy, shy, thinking, exclaim, pout, deadpan, flustered, normal }

/** voice（仅元数据，本期不播放，产品 1.11 预留）。 */
enum class AmadeusVoice { whisper, soft, excited }

/** window 类型（产品 1.9）。 */
enum class AmadeusWindow { none, report, preview, choice }

/** 一组标签（架构 3.8：一个 message = 一组标签，段尾）。AMW 协议当前只消费 sprite。 */
data class AmadeusTag(
  val sprite: AmadeusSprite = AmadeusSprite.normal,
  val voice: AmadeusVoice = AmadeusVoice.soft,
  val window: AmadeusWindow = AmadeusWindow.none,
  val windowId: String = "",
  val windowTitle: String = "",
)

/** 演出段：一次文本事件 = 一次展示（架构 3.8）。 */
data class Dialogue(
  val text: String,
  val tag: AmadeusTag,
  /** 回合工作中说的话（旁白）→ 句末带工作动画；最终回答/历史 = false。 */
  val working: Boolean = false,
)

/** 幕后活动（思考/工具/step），喂事件流小窗（架构 3.19 activity 帧）。 */
data class Activity(
  val kind: String,   // tool / think / step / turn ...
  val title: String,  // 摘要
  val detail: String, // 详情（可展开）
)

/** 选项（ask_user_question）。 */
data class ChoiceOption(val label: String, val description: String? = null)

data class Choice(
  val choiceId: String,
  val question: String,
  val options: List<ChoiceOption>,
)

/** 审批请求（方案 B：workspace-write + App 端批准/拒绝）。 */
data class ApprovalRequest(
  val approvalId: String,
  val toolName: String,
  val reason: String? = null,
)

/** 报告（长文本铁律：Host 内联推送全文，App 报告入口就靠它）。 */
data class Report(
  val reportId: String,
  val title: String,
  val body: String,
)

/** SSE 事件（架构 3.19 契约）。 */
sealed class StreamEvent {
  data class DialogueEvent(val dialogue: Dialogue) : StreamEvent()
  data class ActivityEvent(val activity: Activity) : StreamEvent()
  data class ChoiceEvent(val choice: Choice) : StreamEvent()
  data class ApprovalEvent(val approval: ApprovalRequest) : StreamEvent()
  data class ReportEvent(val report: Report) : StreamEvent()
  data class Ended(val reason: String) : StreamEvent()
  /** 回合结束（agent 停笔）：事件流渐隐清空、末句工作符号去除。 */
  data class TurnEnded(val reason: String) : StreamEvent()
}

/** 会话（读档页用）。 */
data class AmadeusSession(
  val id: String,
  val title: String,
  val workspace: String,
  val updatedAt: Long,
)

/** 会话页（恢复历史用，架构 3.20）。 */
data class SessionPage(
  val events: List<StreamEvent>,
  val hasMore: Boolean,
)
