import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, vi } from 'vitest'
import type { Readable } from 'node:stream'
import { createAmadeusExtension } from '../src/amadeus-extension.js'

type RouteHandle = (request: {
  method: string
  pathname: string
  query?: URLSearchParams
  body?: Uint8Array
}) => Promise<{ status: number; contentType?: string | undefined; body: string | Uint8Array | Readable }>

function makeHandler(business: Record<string, unknown>): RouteHandle {
  const routes = createAmadeusExtension(business as never).routes ?? []
  return async request => {
    for (const route of routes) {
      if (route.method !== request.method) continue
      const matches = (route.kind ?? 'exact') === 'exact'
        ? route.path === request.pathname
        : request.pathname === route.path || request.pathname.startsWith(`${route.path}/`)
      if (!matches) continue
      const response = await route.handle({
        method: request.method,
        pathname: request.pathname,
        query: request.query ?? new URLSearchParams(),
        headers: {},
        body: request.body ?? new Uint8Array(),
        signal: new AbortController().signal,
        deviceId: 'fixture-device',
      })
      return { status: response.status ?? 200, contentType: response.contentType, body: response.body }
    }
    throw new Error(`no route for ${request.method} ${request.pathname}`)
  }
}

function jsonBody(request: { method: string; pathname: string; query?: URLSearchParams; body?: Uint8Array }, body: unknown): typeof request {
  return { ...request, body: new TextEncoder().encode(JSON.stringify(body)) }
}

async function streamText(body: string | Uint8Array | Readable): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of body as AsyncIterable<string | Buffer>) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks).toString('utf8')
}

function makeBusiness() {
  const commands = {
    assertOwned: vi.fn().mockResolvedValue(true),
    rename: vi.fn().mockResolvedValue(undefined),
    archive: vi.fn().mockResolvedValue(undefined),
    prompt: vi.fn().mockResolvedValue(undefined),
    cancel: vi.fn().mockResolvedValue(undefined),
    page: vi.fn().mockResolvedValue({ messages: [{ role: 'assistant' as const, text: '嗨' }], hasMore: false }),
  }
  const workspaces = {
    list: vi.fn().mockResolvedValue([{ id: 'w1', path: '/home/w', title: '工作区' }]),
  }
  const previews = {
    get: vi.fn().mockResolvedValue({ id: 'pv_1', type: 'web', content: 'https://example.test', title: '预览' }),
  }
  const choices = {
    create: vi.fn(),
    resolve: vi.fn(),
    get: vi.fn().mockResolvedValue({ question: '选吗', options: ['A', 'B'] }),
    cancel: vi.fn().mockResolvedValue(undefined),
  }
  const stream = {
    open: vi.fn(async (_id: string, write: (data: string) => void, onFinished?: () => void) => {
      write(JSON.stringify({ type: 'segments', text: '好呀\n[[AMW:{"mood":"happy","sprite":"smile","voice":"soft","sfx":"none","bgm":"none"}]]' }))
      onFinished?.()
      return () => {}
    }),
  }
  return { commands, workspaces, previews, choices, stream, business: { commands, workspaces, previews, choices, stream } }
}

describe('amadeus extension routes', () => {
  it('renames a session', async () => {
    const { commands, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler(jsonBody({ method: 'POST', pathname: '/sessions/s1/rename' }, { title: '新标题' }))
    expect(response.status).toBe(200)
    expect(JSON.parse(response.body as string)).toEqual({ ok: true })
    expect(commands.rename).toHaveBeenCalledWith('s1', '新标题')
  })

  it('rejects a rename with an invalid session id', async () => {
    const { commands, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler(jsonBody({ method: 'POST', pathname: '/sessions/!bad/rename' }, { title: 'x' }))
    expect(response.status).toBe(400)
    expect(commands.rename).not.toHaveBeenCalled()
  })

  it('prompts, archives and cancels sessions', async () => {
    const { commands, business } = makeBusiness()
    const handler = makeHandler(business)
    const prompt = await handler(jsonBody({ method: 'POST', pathname: '/sessions/s1/prompt' }, { text: '继续' }))
    expect(JSON.parse(prompt.body as string)).toEqual({ ok: true })
    expect(commands.prompt).toHaveBeenCalledWith('s1', '继续')
    const archive = await handler({ method: 'POST', pathname: '/sessions/s1/archive' })
    expect(archive.status).toBe(200)
    expect(commands.archive).toHaveBeenCalledWith('s1')
    const cancel = await handler({ method: 'POST', pathname: '/sessions/s1/cancel' })
    expect(cancel.status).toBe(200)
    expect(commands.cancel).toHaveBeenCalledWith('s1')
  })

  it('rejects an empty prompt', async () => {
    const { commands, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler(jsonBody({ method: 'POST', pathname: '/sessions/s1/prompt' }, { text: '' }))
    expect(response.status).toBe(400)
    expect(commands.prompt).not.toHaveBeenCalled()
  })

  it('creates a session with an optional workspaceId', async () => {
    const sessions = {
      list: vi.fn(),
      create: vi.fn().mockResolvedValue({ id: 's1', title: '新会话', mode: 'amadeus', updatedAt: 1 }),
      get: vi.fn(),
    }
    const handler = makeHandler({ sessions })
    const response = await handler(jsonBody({ method: 'POST', pathname: '/sessions' }, { title: '标题', workspaceId: 'w1' }))
    expect(response.status).toBe(201)
    expect(sessions.create).toHaveBeenCalledWith('amadeus', '标题', 'w1')
  })

  it('returns 404 when a session command targets a non-amadeus session', async () => {
    const { commands, business } = makeBusiness()
    commands.assertOwned.mockResolvedValue(false)
    const handler = makeHandler(business)
    const rename = await handler(jsonBody({ method: 'POST', pathname: '/sessions/s1/rename' }, { title: 'x' }))
    expect(rename.status).toBe(404)
    expect(commands.rename).not.toHaveBeenCalled()
    const prompt = await handler(jsonBody({ method: 'POST', pathname: '/sessions/s1/prompt' }, { text: 'x' }))
    expect(prompt.status).toBe(404)
    expect(commands.prompt).not.toHaveBeenCalled()
  })

  it('returns 404 for page on a non-amadeus session', async () => {
    const { commands, business } = makeBusiness()
    commands.assertOwned.mockResolvedValueOnce(false)
    const handler = makeHandler(business)
    const response = await handler({ method: 'GET', pathname: '/sessions/s1/page' })
    expect(response.status).toBe(404)
    expect(commands.page).not.toHaveBeenCalled()
  })

  it('returns 404 for the stream route on a non-amadeus session', async () => {
    const { commands, stream, business } = makeBusiness()
    commands.assertOwned.mockResolvedValueOnce(false)
    const handler = makeHandler(business)
    const response = await handler({ method: 'GET', pathname: '/stream/s1' })
    expect(response.status).toBe(404)
    expect(stream.open).not.toHaveBeenCalled()
  })

  it('pages session history with an optional beforeSeq', async () => {
    const { commands, business } = makeBusiness()
    const handler = makeHandler(business)
    const page = await handler({ method: 'GET', pathname: '/sessions/s1/page' })
    expect(JSON.parse(page.body as string)).toEqual({ messages: [{ role: 'assistant', text: '嗨' }], hasMore: false })
    expect(commands.page).toHaveBeenCalledWith('s1')
    await handler({ method: 'GET', pathname: '/sessions/s1/page', query: new URLSearchParams({ beforeSeq: '42' }) })
    expect(commands.page).toHaveBeenCalledWith('s1', 42)
    const invalid = await handler({ method: 'GET', pathname: '/sessions/s1/page', query: new URLSearchParams({ beforeSeq: 'abc' }) })
    expect(invalid.status).toBe(400)
  })

  it('lists workspaces', async () => {
    const { workspaces, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler({ method: 'GET', pathname: '/workspaces' })
    expect(JSON.parse(response.body as string)).toEqual({ workspaces: [{ id: 'w1', path: '/home/w', title: '工作区' }] })
    expect(workspaces.list).toHaveBeenCalledOnce()
  })

  it('returns a preview by id (bare payload)', async () => {
    const { previews, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler({ method: 'GET', pathname: '/previews/pv_1' })
    expect(JSON.parse(response.body as string)).toEqual({ id: 'pv_1', type: 'web', content: 'https://example.test', title: '预览' })
    previews.get.mockResolvedValueOnce(null)
    const missing = await handler({ method: 'GET', pathname: '/previews/pv_missing' })
    expect(missing.status).toBe(404)
  })

  it('returns a report by id (flat payload)', async () => {
    const reports = {
      list: vi.fn(),
      save: vi.fn(),
      get: vi.fn().mockResolvedValue({ id: 'rpt_1', title: '报告', markdown: '# hi', createdAt: 1 }),
    }
    const handler = makeHandler({ reports })
    const response = await handler({ method: 'GET', pathname: '/reports/rpt_1' })
    expect(JSON.parse(response.body as string)).toEqual({ id: 'rpt_1', title: '报告', markdown: '# hi', createdAt: 1 })
    reports.get.mockResolvedValueOnce(null)
    const missing = await handler({ method: 'GET', pathname: '/reports/rpt_missing' })
    expect(missing.status).toBe(404)
  })

  it('streams SSE frames for a session', async () => {
    const { stream, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler({ method: 'GET', pathname: '/stream/s1' })
    expect(response.status).toBe(200)
    expect(response.contentType).toBe('text/event-stream; charset=utf-8')
    const text = await streamText(response.body)
    expect(text).toContain('retry: 2000')
    expect(text).toContain('data: {"type":"segments","text":"好呀\\n[[AMW:')
    expect(stream.open).toHaveBeenCalledWith('s1', expect.any(Function), expect.any(Function))
  })

  it('returns 503 for the stream route when no stream adapter is wired', async () => {
    const handler = makeHandler({})
    const response = await handler({ method: 'GET', pathname: '/stream/s1' })
    expect(response.status).toBe(503)
    expect(JSON.parse(response.body as string)).toEqual({ error: 'amadeus_stream_unavailable' })
  })

  it('cancels a pending choice by id', async () => {
    const { choices, business } = makeBusiness()
    const handler = makeHandler(business)
    const response = await handler(jsonBody({ method: 'POST', pathname: '/choice/cancel' }, { choiceId: 'cq_1' }))
    expect(response.status).toBe(200)
    expect(JSON.parse(response.body as string)).toEqual({ ok: true })
    expect(choices.cancel).toHaveBeenCalledWith('cq_1')
  })

  it('returns 404 when cancelling a choice that does not exist', async () => {
    const { choices, business } = makeBusiness()
    choices.get.mockResolvedValueOnce(null)
    const handler = makeHandler(business)
    const response = await handler(jsonBody({ method: 'POST', pathname: '/choice/cancel' }, { choiceId: 'cq_missing' }))
    expect(response.status).toBe(404)
    expect(choices.cancel).not.toHaveBeenCalled()
  })

  it('POST /choice/cancel rejects the pending answerer', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'amw-route-e2e-'))
    try {
      const { AmadeusChoicesAdapter } = await import('../src/amadeus-choices.js')
      const ctx = { on: () => {} } as any
      const adapter = new AmadeusChoicesAdapter(ctx, dir)
      const pushed: any[] = []
      adapter.registerStream('sess-e2e', frame => pushed.push(frame))
      const pending = adapter.answerRequest({
        questions: [{ id: 'q1', question: '选吗', options: [{ label: 'A' }, { label: 'B' }] }],
        agent: { session: { id: 'sess-e2e' } },
      })
      for (let attempt = 0; attempt < 50 && pushed.length === 0; attempt++) await new Promise(resolve => setTimeout(resolve, 5))
      expect(pushed).toHaveLength(1)
      const choiceId = (pushed[0] as { choiceId: string }).choiceId
      const handler = makeHandler({ choices: adapter } as never)
      const response = await handler(jsonBody({ method: 'POST', pathname: '/choice/cancel' }, { choiceId }))
      expect(response.status).toBe(200)
      expect(JSON.parse(response.body as string)).toEqual({ ok: true })
      await expect(pending).rejects.toThrow('choice-cancelled')
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })
})
