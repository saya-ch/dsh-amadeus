import type { MobileExtensionDefinition, MobileHostRoute, MobileRouteRequest, MobileRouteResponse } from 'dsh-mobile'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'
import { ensureAmadeusTag, parseAmadeusSegments, parseAmadeusTag } from './amadeus-tags.js'

/** Stable namespace within the shared DSH Mobile gateway. */
export const AMADEUS_EXTENSION_ID = 'amadeus'

/** A real DSH session projected by an Amadeus session adapter. */
export interface AmadeusSessionSummary {
  readonly id: string
  readonly title: string
  readonly mode: typeof AMADEUS_MODE_ID
  readonly updatedAt: number
  readonly lastMessage?: string
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
    create(mode: string, title?: string): Promise<AmadeusSessionSummary>
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
    get(id: string): Promise<{ question: string; options: string[] } | null>
  }
}

const MAX_BODY_BYTES = 64 * 1024
const ID_PATTERN = /^[a-zA-Z0-9_-]{1,128}$/u

class AmadeusRequestError extends Error {
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

function id(value: unknown): string {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) return badRequest()
  return value
}

function unavailable(capability: 'sessions' | 'reports' | 'choices'): never {
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
        : json({ error: 'amadeus_internal_error' }, 500)
    }
  } }
}

/** Routes are authenticated by DSH Mobile before any Amadeus adapter is invoked. */
export function createAmadeusExtension(options: AmadeusGatewayOptions = {}): MobileExtensionDefinition {
  return {
    schemaVersion: 1,
    id: AMADEUS_EXTENSION_ID,
    name: 'Amadeus: Whale',
    version: '0.1.0',
    description: 'Independent Amadeus business routes using the DSH Mobile connection layer',
    routes: [
      route('GET', '/status', () => json({
        id: AMADEUS_EXTENSION_ID,
        version: '0.1.0',
        connection: 'dsh-mobile',
        capabilities: { tags: true, sessions: options.sessions !== undefined, reports: options.reports !== undefined, choices: options.choices !== undefined },
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
        const sessions = options.sessions ?? unavailable('sessions')
        return json({ session: await sessions.create(AMADEUS_MODE_ID, sessionTitle) }, 201)
      }),
      route('GET', '/reports', async () => {
        const reports = options.reports ?? unavailable('reports')
        return json({ reports: await reports.list() })
      }),
      route('GET', '/reports', async request => {
        const reportId = id(request.pathname.slice('/reports/'.length))
        const reports = options.reports ?? unavailable('reports')
        const report = await reports.get(reportId)
        if (report === null) throw new AmadeusRequestError(404, 'not_found')
        return json({ report })
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
