/**
 * Amadeus 专用 Gateway — 在 dsh-mobile 的 MobileAccessGateway 基础上
 * 增加 /amadeus/sessions 选档 + 报告/预览/choice 能力
 */
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import { HttpError, assertLocalAdminTrust, parseRequestTarget, readJsonObject, sendJson, sendFailure } from './http-security.js'
import { ensureAmadeusTag, parseAmadeusTag, parseAmadeusSegments } from './amadeus-tags.js'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'

export interface AmadeusSessionSummary {
  readonly id: string
  readonly title: string
  readonly mode: typeof AMADEUS_MODE_ID
  readonly updatedAt: number
  readonly lastMessage?: string
}

export interface AmadeusReport {
  readonly id: string
  readonly title: string
  readonly markdown: string
  readonly createdAt: number
}

export interface AmadeusGatewayOptions {
  readonly sessions: {
    list(mode: string): Promise<AmadeusSessionSummary[]>
    create(mode: string, title?: string): Promise<AmadeusSessionSummary>
    get(id: string): Promise<AmadeusSessionSummary | null>
  }
  readonly reports?: {
    get(id: string): Promise<AmadeusReport | null>
    save(report: AmadeusReport): Promise<void>
    list(): Promise<AmadeusReport[]>
  }
  // ask_user_question 的 pending 队列，由 Host 拦截工具调用后写入，APP 轮询或 WS 推送
  readonly choices?: {
    create(choiceId: string, question: string, options: string[]): Promise<void>
    resolve(choiceId: string, selected: string): Promise<void>
    get(choiceId: string): Promise<{ question: string, options: string[] } | null>
  }
}

export function createAmadeusRoute(options: AmadeusGatewayOptions): WebRoute {
  return {
    kind: 'prefix',
    path: '/amadeus',
    handler: async (request, response) => {
      try {
        const target = parseRequestTarget(request.url)
        assertLocalAdminTrust(request, request.method === 'POST')

        // GET /amadeus/sessions?mode=amadeus
        if (request.method === 'GET' && target.decodedPathname === '/amadeus/sessions') {
          const url = new URL(request.url ?? '/', 'http://localhost')
          const mode = url.searchParams.get('mode') ?? AMADEUS_MODE_ID
          if (mode !== AMADEUS_MODE_ID) throw new HttpError(400, 'bad_request')
          const list = await options.sessions.list(mode)
          sendJson(response, 200, { sessions: list }, false)
          return
        }

        // POST /amadeus/sessions
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/sessions') {
          const body = await readJsonObject(request, 4096)
          const title = typeof body.title === 'string' ? body.title.slice(0, 64) : undefined
          const created = await options.sessions.create(AMADEUS_MODE_ID, title)
          sendJson(response, 201, { session: created }, false)
          return
        }

        // GET /amadeus/reports  报告列表
        if (request.method === 'GET' && target.decodedPathname === '/amadeus/reports') {
          const list = options.reports ? await options.reports.list() : []
          sendJson(response, 200, { reports: list }, false)
          return
        }

        // GET /amadeus/reports/:id  报告详情
        if (request.method === 'GET' && target.decodedPathname.startsWith('/amadeus/reports/')) {
          const id = target.decodedPathname.slice('/amadeus/reports/'.length)
          if (!id || !/^[a-z0-9_-]{1,64}$/i.test(id)) throw new HttpError(400, 'bad_request')
          const report = options.reports ? await options.reports.get(id) : null
          if (!report) throw new HttpError(404, 'not_found')
          sendJson(response, 200, { report }, false)
          return
        }

        // POST /amadeus/reports  保存报告 (Host 内部调用)
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/reports') {
          if (!options.reports) throw new HttpError(404, 'not_found')
          const body = await readJsonObject(request, 64 * 1024)
          if (typeof body.id !== 'string' || typeof body.markdown !== 'string') throw new HttpError(400, 'bad_request')
          const report: AmadeusReport = {
            id: body.id,
            title: typeof body.title === 'string' ? body.title : '报告',
            markdown: body.markdown,
            createdAt: Date.now(),
          }
          await options.reports.save(report)
          sendJson(response, 201, { report }, false)
          return
        }

        // POST /amadeus/choice  用户在 Galgame 选项卡选择后回调
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/choice') {
          if (!options.choices) throw new HttpError(404, 'not_found')
          const body = await readJsonObject(request, 4096)
          if (typeof body.choiceId !== 'string' || typeof body.selected !== 'string') throw new HttpError(400, 'bad_request')
          await options.choices.resolve(body.choiceId, body.selected)
          sendJson(response, 200, { ok: true }, false)
          return
        }

        // POST /amadeus/tag/ensure  调试：多句分页解析
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/tag/ensure') {
          const body = await readJsonObject(request, 64 * 1024)
          if (typeof body.text !== 'string') throw new HttpError(400, 'bad_request')
          const segments = parseAmadeusSegments(body.text)
          const ensured = ensureAmadeusTag(body.text)
          const parsed = parseAmadeusTag(ensured)
          sendJson(response, 200, { segments, ensured, clean: parsed.clean, tag: parsed.tag }, false)
          return
        }

        throw new HttpError(404, 'not_found')
      } catch (error) {
        const mapped = error instanceof HttpError ? error : new HttpError(500, 'internal_error')
        if (response.headersSent) response.destroy()
        else sendFailure(response, mapped.status, mapped.code, false)
      }
    },
  }
}

export function ensureTagForAmadeus(text: string): string {
  return ensureAmadeusTag(text)
}

/**
 * 拦截 ask_user_question 工具调用，转为 Galgame choice 窗口
 * 由 Host 的 tool 拦截层调用
 */
export function toChoiceTag(choiceId: string, question: string, options: string[]): string {
  return `要怎么选呢... [[AMW:{"mood":"think","sprite":"think","voice":"soft","sfx":"bell","bgm":"none","window":"choice","choiceId":"${choiceId}","options":${JSON.stringify(options)}}]]`
}
