import { describe, expect, it } from 'vitest'
import { AmadeusSessionsAdapter, AmadeusSessionCommands } from '../src/amadeus-sessions.js'
import { AMADEUS_OPENING_PROMPT, createAmadeusOpeningSession } from '../src/amadeus-plugin.js'

function fakeCtx(calls?: string[]) {
  return {
    modeId: 'amadeus',
    sessionQuery: {
      listSessions: async () => [],
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
    const adapter = new AmadeusSessionsAdapter(fakeCtx(calls) as any)
    const commands = new AmadeusSessionCommands(fakeCtx(calls) as any)
    const created = await createAmadeusOpeningSession(adapter, commands, 'amadeus')
    expect(created.id).toBe('s1')
    expect(calls).toEqual(['create', 'prompt'])
  })

  it('delivers the fixed gentle opening line', async () => {
    let received: string | undefined
    const ctx: any = fakeCtx()
    ctx.sessionController.prompt = async (req: any) => { received = req.content[0].text; return { accepted: true } }
    const adapter = new AmadeusSessionsAdapter(ctx)
    const commands = new AmadeusSessionCommands(ctx)
    await createAmadeusOpeningSession(adapter, commands, 'amadeus')
    expect(received).toBe('你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话')
    expect(received).toBe(AMADEUS_OPENING_PROMPT)
  })
})