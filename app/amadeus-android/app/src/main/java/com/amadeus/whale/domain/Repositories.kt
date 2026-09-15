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

  /** 已注册工作区列表（会话选择器用）。 */
  suspend fun listWorkspaces(): List<WorkspaceView>

  /** 列目录一层（App 内目录浏览器；path 缺省 = 主目录）。 */
  suspend fun browseDirectory(path: String? = null): DirectoryListingView

  /** 把目录注册为工作区（App 选定目录后），返回其 id。 */
  suspend fun registerWorkspace(path: String): WorkspaceView

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

/** 工作区视图（会话选择器：默认 Amadeus 工作区 / 其他目录）。 */
data class WorkspaceView(
  val id: String,
  val path: String,
  val title: String,
)

/** 目录浏览一层（App 内目录选择器）：面包屑 + 子目录。 */
data class DirectoryListingView(
  val path: String,
  val home: String,
  val crumbs: List<Pair<String, String>>, // (name, path)
  val entries: List<Triple<String, String, Boolean>>, // (name, path, hidden)
)

/** 选项 Repository（ask_user_question，产品 1.9）。 */
interface ChoiceRepository {
  suspend fun resolve(choiceId: String, selected: String)
  suspend fun cancel(choiceId: String)
}

/** 审批 Repository（方案 B：App 端批准/拒绝）。 */
interface ApprovalRepository {
  suspend fun decide(approvalId: String, allowed: Boolean)
}
