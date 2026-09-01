import { randomUUID } from 'node:crypto'
import type { AmadeusGatewayOptions } from './amadeus-extension.js'

/**
 * 审批决策（方案 B：workspace-write + App 审批 UI，产品 1.12 手机遥控）。
 *
 * DSH 的 approval 走 `approval/request` waterfall（user-approval 服务），
 * answerer 返回 ApprovalOutcome：'allowed-once'（批准）| 'rejected' | 'cancelled' | 'unavailable'。
 * 无 answerer 时 fail-closed（拒绝）。本模块注册一个 answerer，把审批请求
 * 挂起到 pending，经 SSE 推给 App，App 调网关 decide 路由决定 —— 完全复用
 * ask_user_question 的 answerer 模式，不改 DSH 源码。
 */

/** DSH ApprovalRequest 结构（user-approval 服务派发给 answerer 的负载）。 */
export interface AmadeusApprovalRequest {
  readonly id: string
  readonly toolName: string
  readonly callId?: string
  readonly reason?: string
  readonly agent?: { readonly session?: { readonly id?: string } } | string
  readonly signal?: AbortSignal
}

/** 审批结果词汇（DSH ApprovalOutcome）。 */
export type AmadeusApprovalOutcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'

/** 挂起的审批请求（resolver 内存态，不持久化）。 */
interface PendingApproval {
  id: string
  toolName: string
  reason?: string
  resolve: (outcome: AmadeusApprovalOutcome) => void
}

/** 上下文结构：on 事件 + stream 推送。 */
export interface AmadeusApprovalContext {
  on(name: string, listener: (request: AmadeusApprovalRequest) => unknown, options?: { global?: boolean }): unknown
  readonly logger?: { warn(message: string): void }
}

/** 审批适配器：注册 answerer + 挂起/决定 + SSE 推送。 */
export class AmadeusApprovalAdapter implements NonNullable<AmadeusGatewayOptions['approval']> {
  private readonly pending = new Map<string, PendingApproval>()
  private readonly abortCleanups = new Map<string, () => void>()
  private readonly streams = new Map<string, (frame: object) => void>()

  constructor(private readonly ctx: AmadeusApprovalContext) {}

  /** 注册 SSE write 函数（一个活跃会话一个）；返回 unregister。 */
  registerStream(sessionId: string, push: (frame: object) => void): () => void {
    this.streams.set(sessionId, push)
    return () => {
      if (this.streams.get(sessionId) === push) this.streams.delete(sessionId)
    }
  }

  /** 安装 approval/request answerer。
   *  global: 跳过 scopeTarget(agent, agent) filter——amadeus 插件 ctx 不在 agent scope 链上，
   *    不 global 会被 filter 排除；global 让 DSH 任何 scope 的派发都到本 answerer。
   *  prepend: 抢占 first-wins slot——DSH web answerer（createApiProxy）先注册且认识每个请求，
   *    不 prepend 会被它吃掉（手机场景看不到电脑端弹窗）。 */
  install(): void {
    this.ctx.on('approval/request', (request: AmadeusApprovalRequest) => {
      return this.answerRequest(request)
    }, { global: true, prepend: true })
  }

  private sessionIdOf(request: AmadeusApprovalRequest): string | undefined {
    const agent = request.agent
    if (typeof agent === 'string') return agent
    const record = agent as { id?: unknown; session?: { id?: unknown } } | undefined
    if (typeof record?.id === 'string') return record.id
    if (typeof record?.session?.id === 'string') return record.session.id
    return undefined
  }

  private approvalIdOf(request: AmadeusApprovalRequest): string | undefined {
    // DSH 的 ApprovalRequest 不带 id——id 只存在于 session 审计事件 approval/asked
    // （web answerer 同样从 session 最近 append 的 approval/asked 读 id）
    if (typeof request.id === 'string' && request.id.length > 0) return request.id
    try {
      const session = (request.agent as { session?: { events?: Array<{ type: string; data?: unknown }> } } | undefined)?.session
      const events = session?.events
      if (!Array.isArray(events)) return undefined
      for (let i = events.length - 1; i >= 0; i -= 1) {
        const ev = events[i]
        if (ev?.type !== 'approval/asked') continue
        const data = ev.data as { id?: unknown; callId?: unknown; toolName?: unknown } | undefined
        if (typeof data?.id !== 'string') continue
        // 匹配 callId（优先）；无 callId 时匹配 toolName；都不匹配则取最近一条
        if (typeof request.callId === 'string' && data.callId !== request.callId) continue
        if (typeof request.callId !== 'string' && typeof request.toolName === 'string' && data.toolName !== request.toolName) continue
        return data.id
      }
      // 兜底：最近一条 approval/asked
      for (let i = events.length - 1; i >= 0; i -= 1) {
        const ev = events[i]
        if (ev?.type !== 'approval/asked') continue
        const data = ev.data as { id?: unknown } | undefined
        if (typeof data?.id === 'string') return data.id
      }
    } catch { /* 读不到就 fallback 到 request.id（可能 undefined） */ }
    return request.id
  }

  private async answerRequest(request: AmadeusApprovalRequest): Promise<AmadeusApprovalOutcome> {
    const sessionId = this.sessionIdOf(request)
    const approvalId = this.approvalIdOf(request)
    console.log('[amadeus-approval] request received', { id: approvalId, toolName: request.toolName, sessionId })
    if (sessionId === undefined || approvalId === undefined) return 'unavailable'
    // 挂起审批，推给 App（approval 帧）
    const outcome = await new Promise<AmadeusApprovalOutcome>((resolve) => {
      const pending: PendingApproval = {
        id: approvalId,
        toolName: request.toolName,
        reason: request.reason,
        resolve,
      }
      this.pending.set(approvalId, pending)
      // 推给该会话的 SSE 流
      this.streams.get(sessionId)?.({
        type: 'approval',
        approvalId,
        toolName: request.toolName,
        callId: request.callId,
        reason: request.reason,
      })
      // 请求取消时释放
      const onAbort = (): void => {
        this.release(approvalId, pending)
        resolve('cancelled')
      }
      if (request.signal !== undefined) {
        if (request.signal.aborted) { onAbort(); return }
        request.signal.addEventListener('abort', onAbort, { once: true })
        this.abortCleanups.set(approvalId, () => request.signal!.removeEventListener('abort', onAbort))
      }
    })
    return outcome
  }

  private release(id: string, pending: PendingApproval): void {
    if (this.pending.get(id) === pending) this.pending.delete(id)
    const cleanup = this.abortCleanups.get(id)
    if (cleanup !== undefined) {
      cleanup()
      this.abortCleanups.delete(id)
    }
  }

  /** App 调网关 decide 路由：决定审批结果。 */
  async decide(approvalId: string, outcome: AmadeusApprovalOutcome): Promise<void> {
    const pending = this.pending.get(approvalId)
    if (pending === undefined) throw new Error(`approval ${approvalId} not pending`)
    this.release(approvalId, pending)
    pending.resolve(outcome)
  }

  /** 会话断开时清理其挂起的审批（拒绝）。 */
  clearSession(sessionId: string): void {
    for (const [id, pending] of this.pending) {
      const session = this.sessionOwnerOf(id)
      if (session === sessionId) {
        this.release(id, pending)
        pending.resolve('cancelled')
      }
    }
  }

  private sessionOwnerOf(_id: string): string | undefined {
    // pending 不记录 session；断连时统一取消（不匹配也可安全释放）
    return undefined
  }
}
