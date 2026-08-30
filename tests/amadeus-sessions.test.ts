import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'

function fakeCtx() {
  const sessions = new Map<string, any>()
  return {
    modeId: 'amadeus',
    sessionQuery: {
      listSessions: async () => [...sessions.values()].map(h => ({ header: h })),
      readTitle: async (id: string) => `标题-${id}`,
      readSurface: async () => ({ events: [] }),
    },
    sessionController: {
      create: async () => ({ sessionId: 's-new', agentPreset: 'amadeus' }),
      rename: async (req: any) => ({ title: req.title, seq: 1 }),
      cancel: async () => ({ accepted: true }),
      prompt: async () => ({ accepted: true }),
      page: async () => ({ records: [], hasMore: false }),
    },
    workspaceRegistry: { archiveSession: async () => {} },
    __sessions: sessions,
  }
}

describe('sessions adapter', () => {
  it('lists only amadeus sessions', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default', cwd: '/w', createdAt: 2 })
    const adapter = new AmadeusSessionsAdapter(ctx)
    const list = await adapter.list('amadeus')
    expect(list).toHaveLength(1)
    expect(list[0]?.id).toBe('a1')
    expect(list[0]?.mode).toBe('amadeus')
  })
  it('create returns summary with mode', async () => {
    const ctx = fakeCtx() as any
    const adapter = new AmadeusSessionsAdapter(ctx)
    const s = await adapter.create('amadeus')
    expect(s.id).toBe('s-new')
    expect(s.mode).toBe('amadeus')
  })
  it('get returns null for non-amadeus session', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default', cwd: '/w', createdAt: 2 })
    const adapter = new AmadeusSessionsAdapter(ctx)
    expect(await adapter.get('b1')).toBeNull()
  })
})

describe('session commands', () => {
  it('prompt sends queue text', async () => {
    const ctx = fakeCtx() as any
    const cmd = new AmadeusSessionCommands(ctx)
    let received: any
    ctx.sessionController.prompt = async (req: any) => { received = req; return { accepted: true } }
    await cmd.prompt('s1', '帮我写文件')
    expect(received.sessionId).toBe('s1')
    expect(received.mode).toBe('queue')
    expect(received.content[0]).toEqual({ type: 'text', text: '帮我写文件' })
  })
})