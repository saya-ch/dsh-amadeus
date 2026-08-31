import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'

function fakeCtx() {
  const sessions = new Map<string, any>()
  const createReq: any = {}
  return {
    modeId: 'amadeus',
    sessionQuery: {
      listSessions: async () => [...sessions.values()].map(h => ({ header: h })),
      readTitle: async (id: string) => `标题-${id}`,
      readSurface: async () => ({ events: [] }),
    },
    sessionController: {
      create: async (req: any) => { Object.assign(createReq, req); return { sessionId: 's-new', agentPreset: 'amadeus' } },
      rename: async (req: any) => ({ title: req.title, seq: 1 }),
      cancel: async () => ({ accepted: true }),
      prompt: async () => ({ accepted: true }),
      page: async () => ({ records: [], hasMore: false }),
    },
    workspaceRegistry: { archiveSession: async () => {} },
    __sessions: sessions,
    __createReq: createReq,
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
  it('create forwards workspaceId to the session controller', async () => {
    const ctx = fakeCtx() as any
    const adapter = new AmadeusSessionsAdapter(ctx)
    await adapter.create('amadeus', '标题', 'w1')
    expect(ctx.__createReq).toEqual({ agentPreset: 'amadeus', workspaceId: 'w1' })
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
    ctx.__sessions.set('s1', { id: 's1', agentPreset: 'amadeus' })
    const cmd = new AmadeusSessionCommands(ctx)
    let received: any
    ctx.sessionController.prompt = async (req: any) => { received = req; return { accepted: true } }
    await cmd.prompt('s1', '帮我写文件')
    expect(received.sessionId).toBe('s1')
    expect(received.mode).toBe('queue')
    expect(received.content[0]).toEqual({ type: 'text', text: '帮我写文件' })
  })

  it('assertOwned reports membership by agentPreset', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus' })
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default' })
    const cmd = new AmadeusSessionCommands(ctx)
    expect(await cmd.assertOwned('a1')).toBe(true)
    expect(await cmd.assertOwned('b1')).toBe(false)
    expect(await cmd.assertOwned('missing')).toBe(false)
  })

  it('every command rejects a non-amadeus session', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default' })
    const cmd = new AmadeusSessionCommands(ctx)
    await expect(cmd.rename('b1', 'x')).rejects.toThrow()
    await expect(cmd.archive('b1')).rejects.toThrow()
    await expect(cmd.prompt('b1', 'x')).rejects.toThrow()
    await expect(cmd.cancel('b1')).rejects.toThrow()
    await expect(cmd.page('b1')).rejects.toThrow()
  })

  it('page skips records that are not events', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus' })
    ctx.sessionController.page = async () => ({
      records: [
        { type: 'other', event: undefined },
        { type: 'event', event: { type: 'user/message', data: { text: '你好' } } },
      ],
      hasMore: false,
    })
    const cmd = new AmadeusSessionCommands(ctx)
    const page = await cmd.page('a1')
    expect(page.messages).toEqual([{ role: 'user', text: '你好' }])
  })
})