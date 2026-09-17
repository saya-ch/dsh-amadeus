import { basename } from 'node:path'
import { randomUUID } from 'node:crypto'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'
import { assistantMessageText, toolResultLabel, userMessageText } from './amadeus-text.js'
import type { AmadeusGatewayOptions, AmadeusSessionSummary } from './amadeus-extension.js'
import type { AmadeusSessionFollowFrame } from './amadeus-stream.js'

/** 给 promise 加超时：超时返回 undefined（调用方按未命中处理）。 */
function withTimeout<T>(ms: number, promise: Promise<T>): Promise<T | undefined> {
  return new Promise(resolve => {
    const timer = setTimeout(() => resolve(undefined), ms)
    promise.then(v => { clearTimeout(timer); resolve(v) }, () => { clearTimeout(timer); resolve(undefined) })
  })
}

/** Structural surface of the DSH Cordis services the Amadeus adapter consumes. */
export interface AmadeusSessionsContext {
  readonly modeId: string
  /** 默认 Amadeus 工作区目录（~/.dsh/amadeus/workspace）；会话未指定工作区时用它。 */
  readonly defaultWorkspaceDir: string
  readonly sessionQuery: {
    listSessions(signal?: AbortSignal): Promise<Array<{ header: { id: string; agentPreset?: string; cwd?: string; createdAt?: number; updatedAt?: number } }>>
    /** 返回标题快照对象（含 title 字符串）或 undefined；声明为 any 以匹配 dsh 真实签名。 */
    readTitle(sessionId: string): Promise<{ title?: string } | undefined>
    readSurface(sessionId: string): Promise<{ events: Array<{ type: string; data: unknown }>; capturedThroughSeq?: number | null }>
  }
  readonly sessionController: {
    create(req: { workspaceId?: string; agentPreset?: string }): Promise<{ sessionId: string }>
    rename(req: { sessionId: string; title: string }): Promise<{ title: string }>
    cancel(req: { sessionId: string }): Promise<{ accepted: boolean }>
    prompt(req: { requestId: string; sessionId: string; mode: 'queue' | 'steer'; content: Array<{ type: 'text'; text: string }> }, signal?: AbortSignal): Promise<{ accepted: boolean }>
    page(req: { address: { kind: 'session'; sessionId: string }; throughSeq: number; beforeSeq?: number; maxMessages?: number }, signal?: AbortSignal): Promise<{ records: Array<{ type: 'event'; event: { type: string; data: unknown } }>; hasMore: boolean }>
    follow(req: { address: { kind: 'session'; sessionId: string } }, signal?: AbortSignal): AsyncIterable<AmadeusSessionFollowFrame>
  }
  readonly workspaceRegistry: {
    archiveSession(sessionId: string): Promise<void>
    readonly archivedSessionIds: readonly string[]
    /** DSH Workspace 实体是扁平的（id/path/title 直挂，无 header 包装）。 */
    list(): Promise<Array<{ id: string; path: string; title?: string; header?: { id?: unknown; path?: unknown; title?: unknown } }>>
    resolveByPath(path: string): Promise<{ id: string; path: string; title?: string } | undefined>
    create(path: string, title?: string): Promise<{ id: string; path: string; title?: string }>
  }
}

/** DSH Workspace 记录归一化为网关摘要：读扁平字段，容忍历史 header 包装。 */
export function toWorkspaceSummary(record: {
  id?: unknown; path?: unknown; title?: unknown
  header?: { id?: unknown; path?: unknown; title?: unknown }
}): { id: string; path: string; title: string } {
  const source = record.header ?? record
  return {
    id: String(source.id ?? ''),
    path: typeof source.path === 'string' ? source.path : '',
    title: typeof source.title === 'string' ? source.title : '',
  }
}

/** Sessions adapter for the Amadeus gateway; filters DSH sessions by agentPreset. */
export class AmadeusSessionsAdapter implements NonNullable<AmadeusGatewayOptions['sessions']> {
  constructor(
    private readonly ctx: AmadeusSessionsContext,
    private readonly registry: import('./amadeus-session-registry.js').AmadeusSessionRegistryLike,
  ) {}

  async list(_mode: string): Promise<AmadeusSessionSummary[]> {
    const records = await this.ctx.sessionQuery.listSessions()
    // 同 id 去重（dsh 可能因窗口/快照返回重复 record）
    const unique = records.filter((r, i, arr) => arr.findIndex(o => o.header.id === r.header.id) === i)
    // 归档会话（用户在 dsh 里隐藏的）不出现在读档——与 dsh web 可见性一致。
    // 注：workspaceRegistry 经 cordis 注入，getter 可能不可达；拿不到就跳过归档过滤（宁可多显示不空列表）。
    let visible = unique
    try {
      const archived = new Set<string>(this.ctx.workspaceRegistry.archivedSessionIds)
      visible = unique.filter(r => !archived.has(r.header.id))
    } catch { /* 归档列表不可达：不过滤 */ }
    // Amadeus 会话判定：header preset=amadeus 直接收；否则查会话注册表缓存；
    // 缓存未命中的（新会话/首次判定）才读 surface（限时），结果落注册表供下次秒回。
    const mine: typeof visible = []
    const unknown: Array<typeof visible[number]> = []
    for (const record of visible) {
      const p = record.header.agentPreset ?? '(none)'
      if (p === this.ctx.modeId) { mine.push(record); continue }
      const known = await this.registry.isChecked(record.header.id).catch(() => 'unknown' as const)
      if (known === 'amadeus') { mine.push(record); continue }
      if (known === 'not-amadeus') continue
      // 未判定：只在历史鲸鱼娘 preset 池里查（minimal 等不可能含演出）
      if (p === 'whale' || p === 'standard') unknown.push(record)
    }
    if (unknown.length > 0) {
      // 并发读 surface 判鲸鱼娘（限时 1.5s/个，超时跳过）；结果登记入表
      const results = await Promise.all(unknown.map(async record => {
        try {
          const surface = await withTimeout(1500, this.ctx.sessionQuery.readSurface(record.header.id))
          if (surface === undefined) return { record, amadeus: false as const }
          const text = JSON.stringify(surface.events ?? surface)
          return { record, amadeus: text.includes('[[AMW:{') || text.includes('"agentPreset":"amadeus"') }
        } catch { return { record, amadeus: false as const } }
      }))
      for (const r of results) {
        if (r.amadeus) mine.push(r.record)
      }
      await this.registry.record(results.map(r => ({ id: r.record.header.id, amadeus: r.amadeus }))).catch(() => {})
    }
    const out: AmadeusSessionSummary[] = []
    for (const record of mine) {
      const raw = await this.ctx.sessionQuery.readTitle(record.header.id).catch(() => undefined)
      out.push(await this.summary(record, raw))
    }
    return out
  }

  async create(mode: string, title?: string, workspaceId?: string): Promise<AmadeusSessionSummary> {
    // workspaceId 缺省 → 解析/注册默认 Amadeus 工作区（~/.dsh/amadeus/workspace），
    // 这样会话 cwd 落默认工作区且 attach 到它 → dsh 3080 可见（归属 Amadeus 工作区）。
    let resolvedWorkspaceId = workspaceId
    if (resolvedWorkspaceId === undefined) {
      resolvedWorkspaceId = await this.defaultAmadeusWorkspaceId()
    }
    const { sessionId } = await this.ctx.sessionController.create({
      agentPreset: this.ctx.modeId,
      ...(resolvedWorkspaceId === undefined ? {} : { workspaceId: resolvedWorkspaceId }),
    })
    // 同步：网关创建的会话直接登记（header preset=amadeus，下次 list 秒命中）
    await this.registry.record([{ id: sessionId, amadeus: true }]).catch(() => {})
    return { id: sessionId, title: title ?? '新会话', mode: mode as typeof AMADEUS_MODE_ID, updatedAt: Date.now() }
  }

  /** 确保默认 Amadeus 工作区目录存在且已注册，返回其 workspace id（幂等）。 */
  private async defaultAmadeusWorkspaceId(): Promise<string | undefined> {
    try {
      const { mkdir } = await import('node:fs/promises')
      await mkdir(this.ctx.defaultWorkspaceDir, { recursive: true })
      const existing = await this.ctx.workspaceRegistry.resolveByPath(this.ctx.defaultWorkspaceDir)
      if (existing !== undefined) return existing.id
      const created = await this.ctx.workspaceRegistry.create(this.ctx.defaultWorkspaceDir, 'Amadeus')
      return created.id
    } catch (error) {
      console.error('[amadeus-sessions] default workspace unavailable:', error)
      return undefined // 拿不到默认工作区就退回 dsh 默认 cwd（不阻塞创建）
    }
  }

  async get(id: string): Promise<AmadeusSessionSummary | null> {
    const records = await this.ctx.sessionQuery.listSessions()
    const hit = records.find(r => r.header.id === id)
    if (hit === undefined || !(await this.isAmadeus(hit.header))) return null
    const raw = await this.ctx.sessionQuery.readTitle(id).catch(() => undefined)
    return this.summary(hit, raw)
  }

  /** header preset 或注册表判定该会话是否鲸鱼娘会话。 */
  private async isAmadeus(header: { id: string; agentPreset?: string }): Promise<boolean> {
    const p = header.agentPreset ?? '(none)'
    if (p === this.ctx.modeId) return true
    return (await this.registry.isChecked(header.id).catch(() => 'unknown' as const)) === 'amadeus'
  }

  /** 组装对外 summary：标题取快照对象或字符串，工作区 = cwd 目录名，时间用创建（dsh 无 updatedAt）。 */
  private async summary(record: { header: { id: string; cwd?: string; createdAt?: number; updatedAt?: number } }, raw: unknown): Promise<AmadeusSessionSummary> {
    const title = typeof raw === 'string' ? raw : ((raw as { title?: string } | undefined)?.title ?? '')
    const cwd = record.header.cwd
    const workspace = cwd === undefined || cwd.length === 0 ? undefined : basename(cwd)
    return {
      id: record.header.id,
      title,
      mode: this.ctx.modeId as typeof AMADEUS_MODE_ID,
      ...(workspace === undefined ? {} : { workspace }),
      updatedAt: record.header.createdAt ?? record.header.updatedAt ?? Date.now(),
    }
  }
}

export interface AmadeusPageMessages {
  readonly records: Array<{ type: 'event'; event: { type: string; data: unknown } }>
  readonly hasMore: boolean
}

/** Mutating commands for the extension's rename/archive/prompt/cancel/page routes. */
export class AmadeusSessionCommands {
  constructor(
    private readonly ctx: AmadeusSessionsContext,
    private readonly registry: import('./amadeus-session-registry.js').AmadeusSessionRegistryLike,
  ) {}

  /** Whether a session exists and is a registered Amadeus session (header preset or registry hit). */
  async assertOwned(id: string): Promise<boolean> {
    const records = await this.ctx.sessionQuery.listSessions()
    const hit = records.find(r => r.header.id === id)
    if (hit === undefined) return false
    if (hit.header.agentPreset === this.ctx.modeId) return true
    return (await this.registry.isChecked(id).catch(() => 'unknown' as const)) === 'amadeus'
  }

  private async requireOwned(id: string): Promise<void> {
    if (!(await this.assertOwned(id))) throw new Error(`session ${id} is not an amadeus session`)
  }

  async rename(id: string, title: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.rename({ sessionId: id, title })
  }

  async archive(id: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.workspaceRegistry.archiveSession(id)
    // 同步：归档后从注册表移除（下次 list 不再出现——archived 过滤双保险）
    await this.registry.unmark([id]).catch(() => {})
  }

  async prompt(id: string, text: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.prompt({ requestId: `amw-${randomUUID()}`, sessionId: id, mode: 'queue', content: [{ type: 'text', text }] }, new AbortController().signal)
  }

  async cancel(id: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.cancel({ sessionId: id })
  }

  async page(id: string, beforeSeq?: number): Promise<AmadeusPageMessages> {
    await this.requireOwned(id)
    // readSurface 提供最新 seq（capturedThroughSeq）作为 page 的 throughSeq
    const surface = await this.ctx.sessionQuery.readSurface(id)
    const throughSeq = surface.capturedThroughSeq ?? -1
    const res = await this.ctx.sessionController.page({
      address: { kind: 'session', sessionId: id },
      throughSeq,
      ...(beforeSeq === undefined ? {} : { beforeSeq }),
      maxMessages: 50,
    }, new AbortController().signal)
    // 3.20：返回原始事件（App 重建完整 SessionLog：演出段 + 事件流都有）
    return { records: res.records, hasMore: res.hasMore }
  }
}