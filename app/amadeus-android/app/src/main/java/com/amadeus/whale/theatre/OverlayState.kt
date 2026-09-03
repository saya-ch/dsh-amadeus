package com.amadeus.whale.theatre

import com.amadeus.whale.domain.model.Activity
import com.amadeus.whale.domain.model.ApprovalRequest
import com.amadeus.whale.domain.model.Choice

/** 覆盖层状态（架构 3.12：单层不叠加）。 */
sealed class OverlayState {
  /** 设置（分页：演出/连接，架构 3.14）。 */
  data object Settings : OverlayState()

  /** 事件流小窗（产品 1.9：实时最近 10 条，透明底侧边栏）。 */
  data class EventLog(val activities: List<Activity>) : OverlayState()

  /** 对话记录侧栏（产品 1.9：鲸鱼娘话 + 用户发言混合记录）。 */
  data class History(val lines: List<com.amadeus.whale.domain.ChatLine>) : OverlayState()

  /** 报告窗口（产品 1.11：长文本/工具报告，报告专用页质感）。 */
  data class Report(val title: String, val body: String) : OverlayState()

  /** 预览窗口（产品 1.11）。 */
  data class Preview(val title: String, val body: String) : OverlayState()

  /** 选项（产品 1.11：ask_user_question）。 */
  data class ChoicePrompt(val choice: Choice) : OverlayState()

  /** 审批（方案 B：workspace-write，App 端批准/拒绝）。 */
  data class ApprovalPrompt(val approval: ApprovalRequest) : OverlayState()
}
