import { describe, expect, it, vi } from 'vitest'
import { AmadeusApprovalAdapter } from '../src/amadeus-approval.js'

function makeCtx() {
  const listeners = new Map<string, (req: any) => unknown>()
  return {
    on: vi.fn((name: string, listener: (req: any) => unknown) => { listeners.set(name, listener) }),
    __listeners: listeners,
  }
}

describe('approval adapter (方案 B: app-side approve/deny)', () => {
  it('installs an approval/request answerer', () => {
    const ctx = makeCtx() as any
    const adapter = new AmadeusApprovalAdapter(ctx)
    adapter.install()
    expect(ctx.on).toHaveBeenCalledWith('approval/request', expect.any(Function), { global: true, prepend: true })
  })

  it('answers a request only after decide() resolves it', async () => {
    const ctx = makeCtx() as any
    const adapter = new AmadeusApprovalAdapter(ctx)
    adapter.install()
    const listener = ctx.__listeners.get('approval/request')!
    // 注册 stream
    let pushed: any
    adapter.registerStream('s1', frame => { pushed = frame })

    const outcomePromise = listener({ id: 'appr_1', toolName: 'write', reason: '写文件', agent: 's1' })
    // 应已推 approval 帧
    expect(pushed).toMatchObject({ type: 'approval', approvalId: 'appr_1', toolName: 'write', reason: '写文件' })

    // 决定前不应完成
    let settled = false
    outcomePromise.then(() => { settled = true })
    await new Promise(r => setTimeout(r, 10))
    expect(settled).toBe(false)

    await adapter.decide('appr_1', 'allowed-once')
    expect(await outcomePromise).toBe('allowed-once')
  })

  it('rejects when decided rejected', async () => {
    const ctx = makeCtx() as any
    const adapter = new AmadeusApprovalAdapter(ctx)
    adapter.install()
    const listener = ctx.__listeners.get('approval/request')!
    adapter.registerStream('s1', () => {})
    const outcome = listener({ id: 'appr_2', toolName: 'bash', agent: 's1' })
    await adapter.decide('appr_2', 'rejected')
    expect(await outcome).toBe('rejected')
  })

  it('resolves cancelled when the request aborts', async () => {
    const ctx = makeCtx() as any
    const adapter = new AmadeusApprovalAdapter(ctx)
    adapter.install()
    const listener = ctx.__listeners.get('approval/request')!
    adapter.registerStream('s1', () => {})
    const ac = new AbortController()
    const outcome = listener({ id: 'appr_3', toolName: 'fs', agent: 's1', signal: ac.signal })
    ac.abort()
    expect(await outcome).toBe('cancelled')
  })

  it('returns unavailable without a session id', async () => {
    const ctx = makeCtx() as any
    const adapter = new AmadeusApprovalAdapter(ctx)
    adapter.install()
    const listener = ctx.__listeners.get('approval/request')!
    const outcome = await listener({ id: 'appr_4', toolName: 'x' })
    expect(outcome).toBe('unavailable')
  })
})
