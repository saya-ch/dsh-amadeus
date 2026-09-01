package com.amadeus.whale.domain

import com.amadeus.whale.domain.model.AmadeusSession
import com.amadeus.whale.domain.model.Choice
import com.amadeus.whale.domain.model.SessionPage
import com.amadeus.whale.domain.model.StreamEvent

/** 会话 Repository（架构 3.6：domain 接口，data 层 OkHttp 实现，可单测）。 */
interface SessionRepository {
  /** 读档页：amadeus mode 会话列表（按工作区分组由 UI 层做）。 */
  suspend fun list(): List<AmadeusSession>

  /** 最后活动会话（首屏直连，产品 1.10）。 */
  suspend fun lastActive(): AmadeusSession?

  /** 新建会话（默认工作区或指定）。 */
  suspend fun create(workspaceId: String? = null): AmadeusSession

  /** 改名 / 删除（读档页长按）。 */
  suspend fun rename(sessionId: String, title: String)
  suspend fun archive(sessionId: String)

  /** 发送消息。 */
  suspend fun send(sessionId: String, text: String)

  /** 取消当前生成。 */
  suspend fun cancel(sessionId: String)

  /** 历史分页（恢复 + 上滑加载更早，架构 3.20）。 */
  suspend fun page(sessionId: String, beforeSeq: Long? = null): SessionPage

  /** 打开 SSE 事件流（架构 3.19）。返回可关闭句柄。 */
  fun openStream(sessionId: String, onEvent: (StreamEvent) -> Unit): AutoCloseable
}

/** 窗口 Repository（报告/预览，产品 1.9）。 */
interface WindowRepository {
  suspend fun report(reportId: String): ReportView
  suspend fun preview(previewId: String): PreviewView
}

data class ReportView(val id: String, val title: String, val markdown: String, val createdAt: Long)
data class PreviewView(val id: String, val type: String, val content: String, val title: String)

/** 选项 Repository（ask_user_question，产品 1.9）。 */
interface ChoiceRepository {
  suspend fun resolve(choiceId: String, selected: String)
  suspend fun cancel(choiceId: String)
}
