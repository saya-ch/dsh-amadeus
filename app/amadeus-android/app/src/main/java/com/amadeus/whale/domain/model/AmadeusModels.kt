package com.amadeus.whale.domain.model

/** mood（立绘心情），产品 1.5 标签协议。 */
enum class AmadeusMood { shy, think, tool, happy, sad, idle }

/** sprite（立绘形象），产品 1.5 标签协议。 */
enum class AmadeusSprite { shy, think, tool, wag, gray, smile, talk }

/** voice（仅元数据，本期不播放，产品 1.11 预留）。 */
enum class AmadeusVoice { whisper, soft, excited }

/** window 类型（产品 1.9）。 */
enum class AmadeusWindow { none, report, preview, choice }

/** 一组标签（架构 3.8：一个 message = 一组标签，段尾）。 */
data class AmadeusTag(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val voice: AmadeusVoice = AmadeusVoice.soft,
  val window: AmadeusWindow = AmadeusWindow.none,
  val windowId: String = "",
  val windowTitle: String = "",
)

/** 演出段：一次文本事件 = 一次展示（架构 3.8）。 */
data class Dialogue(
  val text: String,
  val tag: AmadeusTag,
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

/** SSE 事件（架构 3.19 契约）。 */
sealed class StreamEvent {
  data class DialogueEvent(val dialogue: Dialogue) : StreamEvent()
  data class ActivityEvent(val activity: Activity) : StreamEvent()
  data class ChoiceEvent(val choice: Choice) : StreamEvent()
  data class ApprovalEvent(val approval: ApprovalRequest) : StreamEvent()
  data class Ended(val reason: String) : StreamEvent()
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
