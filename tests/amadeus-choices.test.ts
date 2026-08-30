import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, beforeEach, afterEach } from 'vitest'
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

function recordingCtx(): { registered: Array<(request: any) => any>; ctx: any } {
  const registered: Array<(request: any) => any> = []
  const ctx = {
    waterfall(_thisArg: unknown, name: string, handler?: (request: any) => any) {
      if (name === 'user-questions/request' && typeof handler === 'function') registered.push(handler)
    },
  }
  return { registered, ctx }
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
})