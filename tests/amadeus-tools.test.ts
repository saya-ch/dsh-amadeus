import { describe, expect, it, vi } from 'vitest'
import { registerAmadeusTools } from '../src/amadeus-tools.js'

function captureTools() {
  const specs: any[] = []
  const ctx = { tools: { register: (spec: any) => { specs.push(spec) } } }
  return { specs, ctx }
}

describe('amadeus tools', () => {
  it('show_preview rejects an unsupported preview type with an error object', async () => {
    const { specs, ctx } = captureTools()
    const previews = { save: vi.fn() }
    registerAmadeusTools(ctx as any, {} as any, previews as any)
    const show = specs.find(s => s.name === 'show_preview')!
    const result = await show.execute({ title: 'x', type: 'bogus', content: 'c' })
    expect(result.error).toBeDefined()
    expect(previews.save).not.toHaveBeenCalled()
    const ok = await show.execute({ title: 'x', type: 'web', content: 'https://x' })
    expect(ok.windowId).toBeDefined()
    expect(previews.save).toHaveBeenCalled()
  })
})