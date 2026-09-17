import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands, toWorkspaceSummary } from '../src/amadeus-sessions.js'

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
    workspaceRegistry: {
      archiveSession: async () => {},
      archivedSessionIds: [] as string[],
    },
    __sessions: sessions,
    __createReq: createReq,
  }
}

/** 内存版注册表（测试用）：adapter/commands 的 registry 依赖。 */
function fakeRegistry(initial: string[] = []) {
  const amadeus = new Set(initial)
  const negatives = new Set<string>()
  return {
    isChecked: async (id: string): Promise<'amadeus' | 'not-amadeus' | 'unknown'> =>
      amadeus.has(id) ? 'amadeus' : negatives.has(id) ? 'not-amadeus' : 'unknown',
    record: async (entries: Array<{ id: string; amadeus: boolean }>) => {
      for (const e of entries) {
        if (e.amadeus) { amadeus.add(e.id); negatives.delete(e.id) }
        else { negatives.add(e.id); amadeus.delete(e.id) }
      }
    },
    unmark: async (ids: string[]) => { for (const id of ids) { amadeus.delete(id); negatives.delete(id) } },
    __amadeus: amadeus,
  }
}

describe('sessions adapter', () => {
  it('lists only amadeus sessions', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default', cwd: '/w', createdAt: 2 })
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    const list = await adapter.list('amadeus')
    expect(list).toHaveLength(1)
    expect(list[0]?.id).toBe('a1')
    expect(list[0]?.mode).toBe('amadeus')
  })
  it('includes early whale/standard sessions whose surface carries stage tags', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('w1', { id: 'w1', agentPreset: 'whale', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('s1', { id: 's1', agentPreset: 'standard', cwd: '/w', createdAt: 2 })
    ctx.__sessions.set('plain', { id: 'plain', agentPreset: 'whale', cwd: '/w', createdAt: 3 })
    ctx.sessionQuery.readSurface = async (id: string) => ({
      events: id === 'plain'
        ? []
        : [{ type: 'assistant/message', data: { text: '呜~ [[AMW:{"mood":"happy","sprite":"smile"}]]' } }],
    })
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    const list = await adapter.list('amadeus')
    const ids = list.map(s => s.id).sort()
    expect(ids).toEqual(['s1', 'w1']) // plain whale（无演出标签）被排除
  })
  it('excludes archived sessions to match the dsh web view', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('a2', { id: 'a2', agentPreset: 'amadeus', cwd: '/w', createdAt: 2 })
    ctx.workspaceRegistry.archivedSessionIds = ['a2']
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    const list = await adapter.list('amadeus')
    expect(list.map(s => s.id)).toEqual(['a1'])
  })
  it('treats a session as amadeus when the current preset switched to amadeus', async () => {
    const ctx = fakeCtx() as any
    // header 是创建快照 standard；surface 里最近一次 preset 切换到了 amadeus
    ctx.__sessions.set('w1', { id: 'w1', agentPreset: 'standard', cwd: '/w', createdAt: 1 })
    ctx.sessionQuery.readSurface = async () => ({
      events: [
        { type: 'agent-preset/selected', data: { agentPreset: 'standard' } },
        { type: 'agent-preset/selected', data: { agentPreset: 'amadeus' } },
      ],
    })
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    const list = await adapter.list('amadeus')
    expect(list.map(s => s.id)).toEqual(['w1'])
  })
  it('registry hit skips surface reads (session table cache)', async () => {
    const ctx = fakeCtx() as any
    // 会话已判定过（注册表命中 amadeus）→ list 不读 surface
    ctx.__sessions.set('c1', { id: 'c1', agentPreset: 'standard', cwd: '/w', createdAt: 1 })
    let surfaceReads = 0
    ctx.sessionQuery.readSurface = async () => { surfaceReads += 1; return { events: [] } }
    const registry = fakeRegistry(['c1'])
    const adapter = new AmadeusSessionsAdapter(ctx, registry)
    const list = await adapter.list('amadeus')
    expect(list.map(s => s.id)).toEqual(['c1'])
    expect(surfaceReads).toBe(0) // 缓存命中：零 surface 读
  })
  it('registry negative caches non-amadeus (no repeated surface reads)', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('n1', { id: 'n1', agentPreset: 'standard', cwd: '/w', createdAt: 1 })
    ctx.__sessions.set('n2', { id: 'n2', agentPreset: 'standard', cwd: '/w', createdAt: 2 })
    let surfaceReads = 0
    ctx.sessionQuery.readSurface = async () => { surfaceReads += 1; return { events: [] } }
    const registry = fakeRegistry()
    const adapter = new AmadeusSessionsAdapter(ctx, registry)
    const first = await adapter.list('amadeus') // 第一次：读 surface 判（都非 amadeus）→ 落 negatives
    expect(first).toHaveLength(0)
    expect(surfaceReads).toBe(2)
    const second = await adapter.list('amadeus') // 第二次：negatives 命中，零读
    expect(second).toHaveLength(0)
    expect(surfaceReads).toBe(2)
  })
  it('create records the new session into the registry', async () => {
    const ctx = fakeCtx() as any
    const registry = fakeRegistry()
    const adapter = new AmadeusSessionsAdapter(ctx, registry)
    await adapter.create('amadeus')
    expect(registry.__amadeus.has('s-new')).toBe(true) // 网关创建即登记
  })
  it('create returns summary with mode', async () => {
    const ctx = fakeCtx() as any
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    const s = await adapter.create('amadeus')
    expect(s.id).toBe('s-new')
    expect(s.mode).toBe('amadeus')
  })
  it('create forwards workspaceId to the session controller', async () => {
    const ctx = fakeCtx() as any
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    await adapter.create('amadeus', '标题', 'w1')
    expect(ctx.__createReq).toEqual({ agentPreset: 'amadeus', workspaceId: 'w1' })
  })
  it('get returns null for non-amadeus session', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default', cwd: '/w', createdAt: 2 })
    const adapter = new AmadeusSessionsAdapter(ctx, fakeRegistry())
    expect(await adapter.get('b1')).toBeNull()
  })
})

describe('session commands', () => {
  it('prompt sends queue text', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('s1', { id: 's1', agentPreset: 'amadeus' })
    const cmd = new AmadeusSessionCommands(ctx, fakeRegistry())
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
    const cmd = new AmadeusSessionCommands(ctx, fakeRegistry())
    expect(await cmd.assertOwned('a1')).toBe(true)
    expect(await cmd.assertOwned('b1')).toBe(false)
    expect(await cmd.assertOwned('missing')).toBe(false)
  })

  it('every command rejects a non-amadeus session', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('b1', { id: 'b1', agentPreset: 'default' })
    const cmd = new AmadeusSessionCommands(ctx, fakeRegistry())
    await expect(cmd.rename('b1', 'x')).rejects.toThrow()
    await expect(cmd.archive('b1')).rejects.toThrow()
    await expect(cmd.prompt('b1', 'x')).rejects.toThrow()
    await expect(cmd.cancel('b1')).rejects.toThrow()
    await expect(cmd.page('b1')).rejects.toThrow()
  })

  it('page returns raw event records (3.20: App rebuilds full SessionLog)', async () => {
    const ctx = fakeCtx() as any
    ctx.__sessions.set('a1', { id: 'a1', agentPreset: 'amadeus' })
    ctx.sessionQuery.readSurface = async () => ({ events: [], capturedThroughSeq: 3 })
    ctx.sessionController.page = async (req: any) => {
      expect(req.throughSeq).toBe(3)
      return {
        records: [
          { type: 'other', event: undefined },
          { type: 'event', event: { type: 'user/message', data: { content: [{ type: 'text', text: '你好' }] } } },
        ],
        hasMore: false,
      }
    }
    const cmd = new AmadeusSessionCommands(ctx, fakeRegistry())
    const page = await cmd.page('a1')
    expect(page.records).toHaveLength(2)
    expect(page.records[1]?.event.type).toBe('user/message')
    expect(page.hasMore).toBe(false)
  })
})
describe('toWorkspaceSummary', () => {
  it('reads the flat upstream Workspace shape', () => {
    expect(toWorkspaceSummary({ id: 'w1', path: '/home/w', title: '工作区' }))
      .toEqual({ id: 'w1', path: '/home/w', title: '工作区' })
  })

  it('tolerates the legacy header-wrapped shape', () => {
    expect(toWorkspaceSummary({ header: { id: 'w2', path: '/home/x', title: '旧' } } as any))
      .toEqual({ id: 'w2', path: '/home/x', title: '旧' })
  })

  it('defaults missing fields to empty strings', () => {
    expect(toWorkspaceSummary({ id: 'w3' } as any)).toEqual({ id: 'w3', path: '', title: '' })
  })
})
