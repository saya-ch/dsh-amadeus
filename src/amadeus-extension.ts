import type { MobileExtensionDefinition, MobileHostRoute, MobileRouteRequest, MobileRouteResponse } from './extensions.js'
import { Readable } from 'node:stream'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'
import { ensureAmadeusTag, parseAmadeusSegments, parseAmadeusTag } from './amadeus-tags.js'
import type { AmadeusPageMessages } from './amadeus-sessions.js'

/** Stable namespace within the Amadeus gateway. */
export const AMADEUS_EXTENSION_ID = 'amadeus'

/** A real DSH session projected by an Amadeus session adapter. */
export interface AmadeusSessionSummary {
  readonly id: string
  readonly title: string
  readonly mode: typeof AMADEUS_MODE_ID
  readonly updatedAt: number
  /** 会话所属工作区显示名（cwd 目录名）；空 = 无归属（dsh 默认 cwd）。 */
  readonly workspace?: string
  readonly lastMessage?: string
}

/** 目录浏览一层（App 内目录选择器）：面包屑 + 子目录。 */
export interface AmadeusDirectoryListing {
  readonly path: string
  readonly home: string
  readonly crumbs: Array<{ name: string; path: string }>
  readonly entries: Array<{ name: string; path: string; hidden: boolean }>
}

/** Persisted report owned by the Amadeus business adapter. */
export interface AmadeusReport {
  readonly id: string
  readonly title: string
  readonly markdown: string
  readonly createdAt: number
}

/** Optional business adapters; absent capabilities fail explicitly, never create mock data. */
export interface AmadeusGatewayOptions {
  readonly sessions?: {
    list(mode: string): Promise<AmadeusSessionSummary[]>
    create(mode: string, title?: string, workspaceId?: string): Promise<AmadeusSessionSummary>
    get(id: string): Promise<AmadeusSessionSummary | null>
  }
  readonly reports?: {
    get(id: string): Promise<AmadeusReport | null>
    save(report: AmadeusReport): Promise<void>
    list(): Promise<AmadeusReport[]>
  }
  readonly choices?: {
    create(choiceId: string, question: string, options: string[]): Promise<void>
    resolve(choiceId: string, selected: string): Promise<void>
    cancel(choiceId: string): Promise<void>
    get(id: string): Promise<{ question: string; options: string[] } | null>
  }
  /** Session mutating commands backing the rename/archive/prompt/cancel/page routes. */
  readonly commands?: {
    assertOwned(id: string): Promise<boolean>
    rename(id: string, title: string): Promise<void>
    archive(id: string): Promise<void>
    prompt(id: string, text: string): Promise<void>
    cancel(id: string): Promise<void>
    page(id: string, beforeSeq?: number): Promise<AmadeusPageMessages>
  }
  /** Workspace listing for the GET /workspaces route. */
  readonly workspaces?: {
    list(): Promise<Array<{ id: string; path: string; title: string }>>
    /** 列目录一层（App 内目录浏览器用；path 缺省 = 主目录）。 */
    browse(path?: string): Promise<AmadeusDirectoryListing>
    /** 把目录注册为工作区（App 选定目录后）。 */
    register(path: string): Promise<{ id: string; path: string; title: string }>
  }
  /** Persisted previews for the GET /previews/:id route. */
  readonly previews?: {
    get(id: string): Promise<{ id: string; type: string; content: string; title: string } | null>
  }
  /** Session follow stream bridge for the GET /stream/:sessionId SSE route. */
  readonly stream?: {
    open(sessionId: string, write: (data: string) => void, onFinished?: () => void): Promise<() => void>
  }
  /** 审批决策（方案 B：App 端批准/拒绝，复用 approval/request waterfall）。 */
  readonly approval?: {
    decide(approvalId: string, outcome: 'allowed-once' | 'rejected' | 'cancelled'): Promise<void>
  }
}

const MAX_BODY_BYTES = 64 * 1024
const ID_PATTERN = /^[a-zA-Z0-9_-]{1,128}$/u

export class AmadeusRequestError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code) }
}

function json(body: unknown, status = 200): MobileRouteResponse {
  return { status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(body) }
}

function badRequest(): never { throw new AmadeusRequestError(400, 'bad_request') }

function readObject(request: MobileRouteRequest): Record<string, unknown> {
  if (request.body.byteLength > MAX_BODY_BYTES) throw new AmadeusRequestError(413, 'payload_too_large')
  let value: unknown
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(request.body)) as unknown }
  catch { return badRequest() }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return badRequest()
  return value as Record<string, unknown>
}

function title(value: unknown): string | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || value.length > 200) return badRequest()
  return value
}

function workspaceId(value: unknown): string | undefined {
  if (value === undefined) return undefined
  if (typeof value !== 'string' || value.length === 0 || value.length > 128) return badRequest()
  return value
}

function id(value: unknown): string {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) return badRequest()
  return value
}

function unavailable(capability: 'sessions' | 'reports' | 'choices' | 'commands' | 'workspaces' | 'previews' | 'stream' | 'approval'): never {
  throw new AmadeusRequestError(503, `amadeus_${capability}_unavailable`)
}

function route(
  method: string,
  path: string,
  handle: MobileHostRoute['handle'],
  kind: 'exact' | 'prefix' = 'exact',
): MobileHostRoute {
  return { method, path, kind, async handle(request) {
    request.signal.throwIfAborted()
    try {
      const response = await handle(request)
      request.signal.throwIfAborted()
      return response
    }
    catch (error) {
      request.signal.throwIfAborted()
      return error instanceof AmadeusRequestError
        ? json({ error: error.code }, error.status)
        : (console.error('[amadeus-extension]', error), json({ error: 'amadeus_internal_error' }, 500))
    }
  } }
}

/** Routes are authenticated by the Amadeus gateway before any adapter is invoked. */
export function createAmadeusExtension(options: AmadeusGatewayOptions = {}): MobileExtensionDefinition {
  return {
    schemaVersion: 1,
    id: AMADEUS_EXTENSION_ID,
    name: 'Amadeus: Whale',
    version: '0.1.0',
    description: 'Independent Amadeus business routes on the Amadeus gateway',
    routes: [
      route('GET', '/status', () => json({
        capabilities: {
          sessions: options.sessions !== undefined,
          reports: options.reports !== undefined,
          choices: options.choices !== undefined,
        },
      })),
      route('GET', '/sessions', async request => {
        const mode = request.query.get('mode') ?? AMADEUS_MODE_ID
        if (mode !== AMADEUS_MODE_ID) return badRequest()
        const sessions = options.sessions ?? unavailable('sessions')
        return json({ sessions: await sessions.list(mode) })
      }),
      route('POST', '/sessions', async request => {
        const body = readObject(request)
        if (body.mode !== undefined && body.mode !== AMADEUS_MODE_ID) return badRequest()
        const sessionTitle = title(body.title)
        const sessionWorkspaceId = workspaceId(body.workspaceId)
        const sessions = options.sessions ?? unavailable('sessions')
        return json({ session: await sessions.create(AMADEUS_MODE_ID, sessionTitle, sessionWorkspaceId) }, 201)
      }),
      route('POST', '/sessions', async request => {
        const tail = request.pathname.slice('/sessions/'.length)
        const slash = tail.indexOf('/')
        if (slash < 0) return badRequest()
        const sessionId = id(tail.slice(0, slash))
        const action = tail.slice(slash + 1)
        const commands = options.commands ?? unavailable('commands')
        if (!(await commands.assertOwned(sessionId))) throw new AmadeusRequestError(404, 'not_found')
        if (action === 'rename') {
          const body = readObject(request)
          const sessionTitle = title(body.title)
          if (sessionTitle === undefined || sessionTitle.length === 0) return badRequest()
          await commands.rename(sessionId, sessionTitle)
        } else if (action === 'archive') {
          await commands.archive(sessionId)
        } else if (action === 'prompt') {
          const body = readObject(request)
          if (typeof body.text !== 'string' || body.text.length === 0 || body.text.length > 8192) return badRequest()
          await commands.prompt(sessionId, body.text)
        } else if (action === 'cancel') {
          await commands.cancel(sessionId)
        } else {
          return badRequest()
        }
        return json({ ok: true })
      }, 'prefix'),
      route('GET', '/sessions', async request => {
        const tail = request.pathname.slice('/sessions/'.length)
        const slash = tail.indexOf('/')
        if (slash < 0 || tail.slice(slash + 1) !== 'page') return badRequest()
        const sessionId = id(tail.slice(0, slash))
        const commands = options.commands ?? unavailable('commands')
        if (!(await commands.assertOwned(sessionId))) throw new AmadeusRequestError(404, 'not_found')
        const rawBefore = request.query.get('beforeSeq')
        if (rawBefore !== null && !/^\d{1,15}$/u.test(rawBefore)) return badRequest()
        const beforeSeq = rawBefore === null ? undefined : Number(rawBefore)
        return json(beforeSeq === undefined ? await commands.page(sessionId) : await commands.page(sessionId, beforeSeq))
      }, 'prefix'),
      route('GET', '/workspaces', async () => {
        const workspaces = options.workspaces ?? unavailable('workspaces')
        return json({ workspaces: await workspaces.list() })
      }),
      route('GET', '/workspaces/browse', async request => {
        const workspaces = options.workspaces ?? unavailable('workspaces')
        const path = request.query.get('path') ?? undefined
        return json({ listing: await workspaces.browse(path) })
      }),
      route('POST', '/workspaces/register', async request => {
        const workspaces = options.workspaces ?? unavailable('workspaces')
        const body = readObject(request)
        const path = body.path
        if (typeof path !== 'string' || path.length === 0 || path.length > 4096) return badRequest()
        return json({ workspace: await workspaces.register(path) })
      }),
      route('GET', '/previews', async request => {
        const previewId = id(request.pathname.slice('/previews/'.length))
        const previews = options.previews ?? unavailable('previews')
        const preview = await previews.get(previewId)
        if (preview === null) throw new AmadeusRequestError(404, 'not_found')
        return json(preview)
      }, 'prefix'),
      route('GET', '/stream', async request => {
        const sessionId = id(request.pathname.slice('/stream/'.length))
        const commands = options.commands
        if (commands !== undefined && !(await commands.assertOwned(sessionId))) throw new AmadeusRequestError(404, 'not_found')
        const stream = options.stream ?? unavailable('stream')
        const source = new Readable({ read() {} })
        let closed = false
        let heartbeat: NodeJS.Timeout | undefined
        let hubClose: (() => void) | undefined
        const push = (chunk: string): void => {
          if (!closed && !source.destroyed) source.push(chunk)
        }
        const writeFrame = (data: string): void => {
          push(`data: ${data}\n\n`)
        }
        const endStream = (): void => {
          if (closed) return
          closed = true
          if (heartbeat !== undefined) clearInterval(heartbeat)
          request.signal.removeEventListener('abort', onAbort)
          if (hubClose !== undefined) void hubClose()
          if (!source.destroyed) source.push(null)
        }
        const onAbort = (): void => { console.error(`[amw-stream] abort session=${sessionId.slice(0,8)} at=${Date.now()}`); endStream() }
        request.signal.addEventListener('abort', onAbort, { once: true })
        push('retry: 2000\n')
        heartbeat = setInterval(() => push(': heartbeat\n\n'), 5_000)
        heartbeat.unref()
        source.once('close', endStream)
        void stream.open(sessionId, writeFrame, endStream).then(close => {
          hubClose = close
          if (closed) void close()
        }).catch(() => endStream())
        return {
          status: 200,
          contentType: 'text/event-stream; charset=utf-8',
          headers: { 'Cache-Control': 'no-store' },
          body: source,
        }
      }, 'prefix'),
      route('GET', '/reports', async () => {
        const reports = options.reports ?? unavailable('reports')
        return json({ reports: await reports.list() })
      }),
      route('GET', '/reports', async request => {
        const reportId = id(request.pathname.slice('/reports/'.length))
        const reports = options.reports ?? unavailable('reports')
        const report = await reports.get(reportId)
        if (report === null) throw new AmadeusRequestError(404, 'not_found')
        return json(report)
      }, 'prefix'),
      route('POST', '/reports', async request => {
        const body = readObject(request)
        const reportId = id(body.id)
        const reportTitle = title(body.title) ?? 'Report'
        if (typeof body.markdown !== 'string') return badRequest()
        const reports = options.reports ?? unavailable('reports')
        const report: AmadeusReport = { id: reportId, title: reportTitle, markdown: body.markdown, createdAt: Date.now() }
        await reports.save(report)
        return json({ report }, 201)
      }),
      route('POST', '/choice', async request => {
        const body = readObject(request)
        const choiceId = id(body.choiceId)
        if (typeof body.selected !== 'string' || body.selected.length > 4096) return badRequest()
        const choices = options.choices ?? unavailable('choices')
        const pending = await choices.get(choiceId)
        if (pending === null) throw new AmadeusRequestError(404, 'not_found')
        if (!pending.options.includes(body.selected)) return badRequest()
        await choices.resolve(choiceId, body.selected)
        return json({ ok: true })
      }),
      route('POST', '/choice/cancel', async request => {
        const body = readObject(request)
        const choiceId = id(body.choiceId)
        const choices = options.choices ?? unavailable('choices')
        const pending = await choices.get(choiceId)
        if (pending === null) throw new AmadeusRequestError(404, 'not_found')
        await choices.cancel(choiceId)
        return json({ ok: true })
      }),
      route('POST', '/approval', async request => {
        // POST /approval/:id/decide — App 决定审批结果（方案 B）
        const tail = request.pathname.slice('/approval/'.length)
        const slash = tail.indexOf('/')
        if (slash < 0 || tail.slice(slash + 1) !== 'decide') return badRequest()
        const approvalId = id(tail.slice(0, slash))
        const body = readObject(request)
        const approval = options.approval ?? unavailable('approval')
        if (body.outcome !== 'allowed-once' && body.outcome !== 'rejected' && body.outcome !== 'cancelled') {
          return badRequest()
        }
        await approval.decide(approvalId, body.outcome)
        return json({ ok: true })
      }, 'prefix'),
      route('POST', '/tag/ensure', request => {
        const body = readObject(request)
        if (typeof body.text !== 'string') return badRequest()
        const ensured = ensureAmadeusTag(body.text)
        const parsed = parseAmadeusTag(ensured)
        return json({ segments: parseAmadeusSegments(body.text), ensured, clean: parsed.clean, tag: parsed.tag })
      }),
    ],
  }
}
