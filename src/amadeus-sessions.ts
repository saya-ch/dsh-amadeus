import { randomUUID } from 'node:crypto'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'
import type { AmadeusGatewayOptions, AmadeusSessionSummary } from './amadeus-extension.js'
import type { AmadeusSessionFollowFrame } from './amadeus-stream.js'

/** Structural surface of the DSH Cordis services the Amadeus adapter consumes. */
export interface AmadeusSessionsContext {
  readonly modeId: string
  readonly sessionQuery: {
    listSessions(signal?: AbortSignal): Promise<Array<{ header: { id: string; agentPreset?: string; cwd?: string; updatedAt?: number } }>>
    readTitle(sessionId: string): Promise<string>
    readSurface(sessionId: string): Promise<{ events: Array<{ type: string; data: unknown }> }>
  }
  readonly sessionController: {
    create(req: { workspaceId?: string; agentPreset?: string }): Promise<{ sessionId: string }>
    rename(req: { sessionId: string; title: string }): Promise<{ title: string }>
    cancel(req: { sessionId: string }): Promise<{ accepted: boolean }>
    prompt(req: { requestId: string; sessionId: string; mode: 'queue' | 'steer'; content: Array<{ type: 'text'; text: string }> }): Promise<{ accepted: boolean }>
    page(req: { address: { kind: 'session'; sessionId: string }; throughSeq: number; beforeSeq?: number; maxMessages?: number }): Promise<{ records: Array<{ type: 'event'; event: { type: string; data: unknown } }>; hasMore: boolean }>
    follow(req: { address: { kind: 'session'; sessionId: string } }): AsyncIterable<AmadeusSessionFollowFrame>
  }
  readonly workspaceRegistry: {
    archiveSession(sessionId: string): Promise<void>
    list(): Promise<Array<{ header: { id: string; path?: string; title?: string } }>>
  }
}

/** Sessions adapter for the Amadeus gateway; filters DSH sessions by agentPreset. */
export class AmadeusSessionsAdapter implements NonNullable<AmadeusGatewayOptions['sessions']> {
  constructor(private readonly ctx: AmadeusSessionsContext) {}

  async list(_mode: string): Promise<AmadeusSessionSummary[]> {
    const records = await this.ctx.sessionQuery.listSessions()
    const mine = records.filter(r => r.header.agentPreset === this.ctx.modeId)
    const out: AmadeusSessionSummary[] = []
    for (const record of mine) {
      const title = await this.ctx.sessionQuery.readTitle(record.header.id)
      out.push({ id: record.header.id, title, mode: this.ctx.modeId as typeof AMADEUS_MODE_ID, updatedAt: record.header.updatedAt ?? Date.now() })
    }
    return out
  }

  async create(mode: string, title?: string, workspaceId?: string): Promise<AmadeusSessionSummary> {
    const { sessionId } = await this.ctx.sessionController.create({
      agentPreset: this.ctx.modeId,
      ...(workspaceId === undefined ? {} : { workspaceId }),
    })
    return { id: sessionId, title: title ?? '新会话', mode: mode as typeof AMADEUS_MODE_ID, updatedAt: Date.now() }
  }

  async get(id: string): Promise<AmadeusSessionSummary | null> {
    const records = await this.ctx.sessionQuery.listSessions()
    const hit = records.find(r => r.header.id === id && r.header.agentPreset === this.ctx.modeId)
    if (hit === undefined) return null
    const title = await this.ctx.sessionQuery.readTitle(id)
    return { id, title, mode: this.ctx.modeId as typeof AMADEUS_MODE_ID, updatedAt: hit.header.updatedAt ?? Date.now() }
  }
}

export interface AmadeusPageMessages {
  readonly messages: Array<{ role: 'user' | 'assistant' | 'tool'; text: string }>
  readonly hasMore: boolean
}

/** Mutating commands for the extension's rename/archive/prompt/cancel/page routes. */
export class AmadeusSessionCommands {
  constructor(private readonly ctx: AmadeusSessionsContext) {}

  /** Whether a session exists and belongs to the amadeus mode preset. */
  async assertOwned(id: string): Promise<boolean> {
    const records = await this.ctx.sessionQuery.listSessions()
    return records.some(r => r.header.id === id && r.header.agentPreset === this.ctx.modeId)
  }

  private async requireOwned(id: string): Promise<void> {
    if (!(await this.assertOwned(id))) throw new Error(`session ${id} is not an amadeus session`)
  }

  async rename(id: string, title: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.rename({ sessionId: id, title })
  }

  async archive(id: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.workspaceRegistry.archiveSession(id)
  }

  async prompt(id: string, text: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.prompt({ requestId: `amw-${randomUUID()}`, sessionId: id, mode: 'queue', content: [{ type: 'text', text }] })
  }

  async cancel(id: string): Promise<void> {
    await this.requireOwned(id)
    await this.ctx.sessionController.cancel({ sessionId: id })
  }

  async page(id: string, beforeSeq?: number): Promise<AmadeusPageMessages> {
    await this.requireOwned(id)
    const res = await this.ctx.sessionController.page({
      address: { kind: 'session', sessionId: id },
      throughSeq: Number.MAX_SAFE_INTEGER,
      ...(beforeSeq === undefined ? {} : { beforeSeq }),
      maxMessages: 50,
    })
    const messages = res.records.flatMap((rec): Array<{ role: 'user' | 'assistant' | 'tool'; text: string }> => {
      if (rec.type !== 'event') return []
      const event = rec.event
      const data = event.data as { text?: string; message?: { text?: string }; name?: string }
      if (event.type === 'user/message') return [{ role: 'user', text: data.text ?? '' }]
      if (event.type === 'assistant/message') return [{ role: 'assistant', text: data.message?.text ?? '' }]
      if (event.type === 'tool/result') return [{ role: 'tool', text: `[工具] ${data.name ?? ''}` }]
      return []
    })
    return { messages, hasMore: res.hasMore }
  }
}