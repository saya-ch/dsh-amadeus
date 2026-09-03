import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'
import { AMADEUS_OPENING_PROMPT, bridgeAmadeusChoicesToStream, createAmadeusOpeningSession } from '../src/amadeus-plugin.js'

/** 最小注册表 fake（本文件只测 opening 流程，注册表不参与断言）。 */
function reg() {
  return {
    isChecked: async () => 'unknown' as const,
    record: async () => {},
    unmark: async () => {},
  }
}

function fakeCtx(calls?: string[]) {
  const sessions = new Map<string, any>([['s1', { id: 's1', agentPreset: 'amadeus' }]])
  return {
    modeId: 'amadeus',
    sessionQuery: {
      listSessions: async () => [...sessions.values()].map(h => ({ header: h })),
      readTitle: async () => 't',
      readSurface: async () => ({ events: [] }),
    },
    sessionController: {
      create: async () => { calls?.push('create'); return { sessionId: 's1' } },
      prompt: async () => { calls?.push('prompt'); return { accepted: true } },
      rename: async () => ({ title: 't', seq: 1 }),
      cancel: async () => ({ accepted: true }),
      page: async () => ({ records: [], hasMore: false }),
    },
    workspaceRegistry: { archiveSession: async () => {} },
  }
}

describe('opening prompt (方案 A)', () => {
  it('creates then immediately prompts an opening line', async () => {
    const calls: string[] = []
    const adapter = new AmadeusSessionsAdapter(fakeCtx(calls) as any, reg())
    const commands = new AmadeusSessionCommands(fakeCtx(calls) as any, reg())
    const created = await createAmadeusOpeningSession(adapter, commands, 'amadeus')
    expect(created.id).toBe('s1')
    expect(calls).toEqual(['create', 'prompt'])
  })

  it('delivers the fixed gentle opening line', async () => {
    let received: string | undefined
    const ctx: any = fakeCtx()
    ctx.sessionController.prompt = async (req: any) => { received = req.content[0].text; return { accepted: true } }
    const adapter = new AmadeusSessionsAdapter(ctx, reg())
    const commands = new AmadeusSessionCommands(ctx, reg())
    await createAmadeusOpeningSession(adapter, commands, 'amadeus')
    expect(received).toBe('你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话')
    expect(received).toBe(AMADEUS_OPENING_PROMPT)
  })

  it('forwards workspaceId when creating the opening session', async () => {
    const ctx: any = fakeCtx()
    const createReq: any = {}
    ctx.sessionController.create = async (req: any) => { Object.assign(createReq, req); return { sessionId: 's1' } }
    const adapter = new AmadeusSessionsAdapter(ctx, reg())
    const commands = new AmadeusSessionCommands(ctx, reg())
    await createAmadeusOpeningSession(adapter, commands, 'amadeus', '标题', 'w1')
    expect(createReq).toEqual({ agentPreset: 'amadeus', workspaceId: 'w1' })
  })

  it('bridges the SSE stream with the choices adapter (register on open, unregister on close)', async () => {
    const registered: Array<{ sessionId: string; push: (frame: object) => void }> = []
    let unregistered = 0
    const choices = {
      registerStream: (sessionId: string, push: (frame: object) => void) => {
        registered.push({ sessionId, push })
        return () => { unregistered++ }
      },
    }
    const frames: string[] = []
    const stream = {
      open: async (_sessionId: string, _write: (data: string) => void, _onFinished?: () => void) => () => {},
    }
    const approval = { registerStream: () => () => {} }
    const open = bridgeAmadeusChoicesToStream(choices as any, approval as any, stream as any)
    const close = await open('s1', data => frames.push(data))
    expect(registered).toHaveLength(1)
    expect(registered[0]!.sessionId).toBe('s1')
    registered[0]!.push({ type: 'choice', choiceId: 'cq_1', question: '问', options: [{ label: 'A' }] })
    expect(frames).toEqual([JSON.stringify({ type: 'choice', choiceId: 'cq_1', question: '问', options: [{ label: 'A' }] })])
    await close()
    expect(unregistered).toBe(1)
  })

  it('bridge unregisters when the stream finishes naturally', async () => {
    let unregistered = 0
    const choices = {
      registerStream: () => () => { unregistered++ },
    }
    let naturalFinish: (() => void) | undefined
    const stream = {
      open: async (_sessionId: string, _write: (data: string) => void, onFinished?: () => void) => {
        naturalFinish = onFinished
        return () => {}
      },
    }
    const approval = { registerStream: () => () => {} }
    const open = bridgeAmadeusChoicesToStream(choices as any, approval as any, stream as any)
    await open('s1', () => {})
    naturalFinish?.()
    expect(unregistered).toBe(1)
  })

  it('bridge unregisters when the stream open fails', async () => {
    let unregistered = 0
    const choices = {
      registerStream: () => () => { unregistered++ },
    }
    const stream = {
      open: async () => { throw new Error('follow failed') },
    }
    const approval = { registerStream: () => () => {} }
    const open = bridgeAmadeusChoicesToStream(choices as any, approval as any, stream as any)
    await expect(open('s1', () => {})).rejects.toThrow('follow failed')
    expect(unregistered).toBe(1)
  })
})