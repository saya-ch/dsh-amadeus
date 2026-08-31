import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { AmadeusChoicesAdapter, AskUserQuestionAbortedError } from '../src/amadeus-choices.js'

let dir: string
beforeEach(async () => { dir = await mkdtemp(join(tmpdir(), 'amw-')) })
afterEach(async () => { await rm(dir, { recursive: true, force: true }) })

async function persistedChoiceId(dir: string): Promise<string> {
  for (let attempt = 0; attempt < 50; attempt++) {
    try {
      const all = JSON.parse(await readFile(join(dir, 'choices.json'), 'utf8')) as Array<{ id: string }>
      if (all.length > 0) return all[0]!.id
    } catch { /* not persisted yet */ }
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  throw new Error('choice not persisted')
}

function recordingCtx(): { registered: Array<(request: any) => any>; registeredOptions: any[]; ctx: any } {
  const registered: Array<(request: any) => any> = []
  const registeredOptions: any[] = []
  const ctx = {
    on(name: string, handler?: (request: any) => any, options?: any) {
      if (name === 'user-questions/request' && typeof handler === 'function') { registered.push(handler); registeredOptions.push(options) }
    },
  }
  return { registered, registeredOptions, ctx }
}

describe('choices adapter', () => {
  it('create then get returns question and options', async () => {
    const a = new AmadeusChoicesAdapter({} as any, dir)
    await a.create('c1', '要不要继续？', ['继续', '停下'])
    const got = await a.get('c1')
    expect(got?.question).toBe('要不要继续？')
    expect(got?.options).toEqual(['继续', '停下'])
  })

  it('resolve delivers the answer to a waiting resolver', async () => {
    const a = new AmadeusChoicesAdapter({} as any, dir)
    await a.create('c2', '选哪个', ['A', 'B'])
    const p = a.wait('c2')
    await a.resolve('c2', 'A')
    expect((await p).answers[0]).toEqual({ id: 'q1', selected: ['A'] })
  })

  it('resolve unknown choice throws', async () => {
    const a = new AmadeusChoicesAdapter({} as any, dir)
    await expect(a.resolve('nope', 'x')).rejects.toThrow()
  })

  it('cancel rejects a pending wait', async () => {
    const a = new AmadeusChoicesAdapter({} as any, dir)
    await a.create('c3', '选哪个', ['A', 'B'])
    const pending = a.wait('c3')
    await a.cancel('c3')
    await expect(pending).rejects.toThrow('choice-cancelled')
  })

  it('cancel is idempotent for unknown choice', async () => {
    const a = new AmadeusChoicesAdapter({} as any, dir)
    await expect(a.cancel('nope')).resolves.toBeUndefined()
  })

  it('installed answerer answers a user-questions/request once the app resolves', async () => {
    const { registered, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()
    expect(registered).toHaveLength(1)

    const request = {
      agent: 'amadeus',
      signal: new AbortController().signal,
      questions: [{ id: 'q7', question: '要继续吗？', options: [{ label: '继续' }, { label: '停下' }] }],
    }
    const pending = registered[0]!(request)
    const choiceId = await persistedChoiceId(dir)
    expect((await a.get(choiceId))?.options).toEqual(['继续', '停下'])
    await a.resolve(choiceId, '继续')

    const answer = await pending
    expect(answer.answers[0]).toEqual({ id: 'q7', selected: ['继续'] })
  })

  it('installed answerer rejects with ASK_ABORTED when the request aborts while waiting', async () => {
    const { registered, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()

    const controller = new AbortController()
    const request = {
      agent: 'amadeus',
      signal: controller.signal,
      questions: [{ id: 'q9', question: '选吗', options: [{ label: 'A' }] }],
    }
    const pending = registered[0]!(request)
    controller.abort()
    await expect(pending).rejects.toThrow(AskUserQuestionAbortedError)
  })

  it('registers the answerer with { global: true } so agent-scoped dispatches still reach it', async () => {
    const { registeredOptions, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()
    expect(registeredOptions).toEqual([{ global: true }])
  })

  it('pushes a choice frame to the stream registered for the request agent session', async () => {
    const { registered, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()
    const pushed: any[] = []
    const unregister = a.registerStream('sess-1', frame => pushed.push(frame))

    const request = {
      agent: { session: { id: 'sess-1' } },
      signal: new AbortController().signal,
      questions: [{ id: 'q8', question: '要继续吗？', options: [{ label: '继续', description: '保持节奏' }, { label: '停下' }] }],
    }
    const pending = registered[0]!(request)
    const choiceId = await persistedChoiceId(dir)
    expect(pushed).toHaveLength(1)
    expect(pushed[0]).toEqual({
      type: 'choice',
      choiceId,
      question: '要继续吗？',
      options: [{ label: '继续', description: '保持节奏' }, { label: '停下' }],
    })
    await a.resolve(choiceId, '继续')
    await pending
    unregister()
  })

  it('unregister stops future choice pushes for a session', async () => {
    const { registered, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()
    const pushed: any[] = []
    const unregister = a.registerStream('sess-1', frame => pushed.push(frame))
    unregister()

    const request = {
      agent: { session: { id: 'sess-1' } },
      signal: new AbortController().signal,
      questions: [{ id: 'q12', question: '选吗', options: [{ label: 'A' }] }],
    }
    const pending = registered[0]!(request)
    const choiceId = await persistedChoiceId(dir)
    expect(pushed).toHaveLength(0)
    await a.resolve(choiceId, 'A')
    await expect(pending).resolves.toEqual({ answers: [{ id: 'q12', selected: ['A'] }] })
  })

  it('keeps a question pending when no stream is registered (resolve still answers)', async () => {
    const { registered, ctx } = recordingCtx()
    const a = new AmadeusChoicesAdapter(ctx, dir)
    a.install()
    const request = {
      agent: { session: { id: 'no-stream' } },
      signal: new AbortController().signal,
      questions: [{ id: 'q13', question: '问', options: [{ label: 'A' }] }],
    }
    const pending = registered[0]!(request)
    const choiceId = await persistedChoiceId(dir)
    await a.resolve(choiceId, 'A')
    await expect(pending).resolves.toEqual({ answers: [{ id: 'q13', selected: ['A'] }] })
  })

  it('answers a scope-filtered waterfall dispatch because the listener is global', async () => {
    const { Context } = await import('@deepseek-ai/cordis')
    const context = new Context()
    const a = new AmadeusChoicesAdapter(context as any, dir)
    a.install()

    const fallback = vi.fn(async () => { throw new Error('fallback should not be reached') })
    const scopeThis = { [Context.filter]: () => false }
    const request = {
      agent: 'amadeus',
      signal: new AbortController().signal,
      questions: [{ id: 'q11', question: '要继续吗？', options: [{ label: '继续' }] }],
    }
    const pending = (context as any).waterfall(scopeThis, 'user-questions/request', request, fallback)
    const choiceId = await persistedChoiceId(dir)
    await a.resolve(choiceId, '继续')

    const answer = await pending
    expect(answer.answers[0]).toEqual({ id: 'q11', selected: ['继续'] })
    expect(fallback).not.toHaveBeenCalled()
    await context.fiber.dispose()
  })

  it('answers a dispatched waterfall through the real cordis Context.on registration', async () => {
    const { Context } = await import('@deepseek-ai/cordis')
    const context = new Context()
    const a = new AmadeusChoicesAdapter(context as any, dir)
    a.install()

    const fallback = vi.fn(async () => { throw new Error('fallback should not be reached') })
    const request = {
      agent: 'amadeus',
      signal: new AbortController().signal,
      questions: [{ id: 'q10', question: '要继续吗？', options: [{ label: '继续' }, { label: '停下' }] }],
    }
    const pending = (context as any).waterfall('user-questions/request', request, fallback)
    const choiceId = await persistedChoiceId(dir)
    await a.resolve(choiceId, '继续')

    const answer = await pending
    expect(answer.answers[0]).toEqual({ id: 'q10', selected: ['继续'] })
    expect(fallback).not.toHaveBeenCalled()
    await context.fiber.dispose()
  })

  it('throws on a corrupt choices file instead of silently returning a fallback', async () => {
    const first = new AmadeusChoicesAdapter({} as any, dir)
    await first.create('c1', '问', ['A'])
    await writeFile(join(dir, 'choices.json'), '{not json')
    const fresh = new AmadeusChoicesAdapter({} as any, dir)
    await expect(fresh.get('c1')).rejects.toThrow()
  })
})

describe('session routing of user-questions/request', () => {
  it('pushes a choice frame to the stream registered for agent.session.id', async () => {
    const ctx = { on: () => {} } as any
    const adapter = new AmadeusChoicesAdapter(ctx, dir)
    adapter.install()
    const pushed: object[] = []
    adapter.registerStream('session-1', frame => pushed.push(frame))
    const promise = adapter.answerRequest({
      questions: [{ id: 'q1', question: '选择', options: [{ label: 'A' }, { label: 'B' }] }],
      agent: { session: { id: 'session-1' } },
    })
    for (let attempt = 0; attempt < 50 && pushed.length === 0; attempt++) await new Promise(resolve => setTimeout(resolve, 5))
    expect(pushed).toHaveLength(1)
    const frame = pushed[0] as { type: string; choiceId: string; question: string; options: Array<{ label: string }> }
    expect(frame.type).toBe('choice')
    expect(frame.question).toBe('选择')
    expect(frame.options.map(o => o.label)).toEqual(['A', 'B'])
    await adapter.resolve(frame.choiceId, 'A')
    const answer = await promise
    expect(answer.answers[0]!.id).toBe('q1')
  })

  it('falls back to agent.id when session.id is absent', async () => {
    const ctx = { on: () => {} } as any
    const adapter = new AmadeusChoicesAdapter(ctx, dir)
    const pushed: object[] = []
    adapter.registerStream('agent-9', frame => pushed.push(frame))
    const promise = adapter.answerRequest({
      questions: [{ id: 'q1', question: 'x', options: [{ label: 'A' }] }],
      agent: 'agent-9' as any,
    })
    for (let attempt = 0; attempt < 50 && pushed.length === 0; attempt++) await new Promise(resolve => setTimeout(resolve, 5))
    expect(pushed).toHaveLength(1)
    const frame = pushed[0] as { type: string; choiceId: string }
    expect(frame.type).toBe('choice')
    await adapter.cancel((frame as { choiceId: string }).choiceId)
    await expect(promise).rejects.toThrow('choice-cancelled')
  })
})
