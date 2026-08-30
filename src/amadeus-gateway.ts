/**
 * Amadeus 专用 Gateway — 在 dsh-mobile 的 MobileAccessGateway 基础上
 * 增加 /amadeus/sessions 选档 API，强制 mode=amadeus
 *
 * 复用 dsh-mobile 的 TLS/配对/发现/远程通道，仅在 adminRoute 上扩展
 */
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import { HttpError, LOCAL_ADMIN_PREFIX, assertLocalAdminTrust, parseRequestTarget, readJsonObject, sendJson, sendFailure } from './http-security.js'
import { ensureAmadeusTag, parseAmadeusTag } from './amadeus-tags.js'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'

// 简化的会话元数据，实际应从 DSH 的 session 存储中查询
export interface AmadeusSessionSummary {
  readonly id: string
  readonly title: string
  readonly mode: typeof AMADEUS_MODE_ID
  readonly updatedAt: number
  readonly lastMessage?: string
}

export interface AmadeusGatewayOptions {
  readonly sessions: {
    list(mode: string): Promise<AmadeusSessionSummary[]>
    create(mode: string, title?: string): Promise<AmadeusSessionSummary>
    get(id: string): Promise<AmadeusSessionSummary | null>
  }
}

/**
 * 创建 /amadeus 前缀的 WebRoute，可与现有的 LOCAL_ADMIN_PREFIX 并存
 */
export function createAmadeusRoute(options: AmadeusGatewayOptions): WebRoute {
  return {
    kind: 'prefix',
    path: '/amadeus',
    handler: async (request, response) => {
      try {
        const target = parseRequestTarget(request.url)
        // 复用 dsh-mobile 的本地信任校验
        assertLocalAdminTrust(request, request.method === 'POST')

        if (target.search !== '' && target.decodedPathname !== '/amadeus/sessions') {
          throw new HttpError(400, 'bad_request')
        }

        // GET /amadeus/sessions?mode=amadeus  选档列表
        if (request.method === 'GET' && target.decodedPathname === '/amadeus/sessions') {
          const url = new URL(request.url ?? '/', 'http://localhost')
          const mode = url.searchParams.get('mode') ?? AMADEUS_MODE_ID
          if (mode !== AMADEUS_MODE_ID) throw new HttpError(400, 'bad_request')
          const list = await options.sessions.list(mode)
          sendJson(response, 200, { sessions: list }, false)
          return
        }

        // POST /amadeus/sessions  新建存档
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/sessions') {
          const body = await readJsonObject(request, 4096)
          const title = typeof body.title === 'string' ? body.title.slice(0, 64) : undefined
          const created = await options.sessions.create(AMADEUS_MODE_ID, title)
          sendJson(response, 201, { session: created }, false)
          return
        }

        // POST /amadeus/tag/ensure  调试：确保标签
        if (request.method === 'POST' && target.decodedPathname === '/amadeus/tag/ensure') {
          const body = await readJsonObject(request, 8192)
          if (typeof body.text !== 'string') throw new HttpError(400, 'bad_request')
          const ensured = ensureAmadeusTag(body.text)
          const parsed = parseAmadeusTag(ensured)
          sendJson(response, 200, { ensured: ensured, clean: parsed.clean, tag: parsed.tag }, false)
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

/**
 * 在 amadeus Mode 的 LLM 输出管道中强制标签
 * 由插件的 llm hook 调用
 */
export function ensureTagForAmadeus(text: string): string {
  return ensureAmadeusTag(text)
}
