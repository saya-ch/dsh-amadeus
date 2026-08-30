import { describe, expect, it } from 'vitest'
import { AmadeusStreamHub } from '../src/amadeus-stream.js'

function framesFor(...frames: any[]): any {
  return {
    sessionController: {
      follow: async function* () {
        for (const f of frames) yield f
      },
    },
  }
}

const flush = (): Promise<void> => new Promise<void>(resolve => setImmediate(resolve))

describe('stream hub', () => {
  it('emits segment frames from follow events', async () => {
    const frames = [
      { type: 'event', event: { type: 'assistant/message', data: { message: { text: '好呀\n[[AMW:{"mood":"happy","sprite":"wag"}]]' } } } },
    ]
    const hub = new AmadeusStreamHub(framesFor(...frames))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const payload = JSON.parse(written[0]!) as { type: string; text: string }
    expect(payload.type).toBe('segments')
    expect(payload.text).toContain('好呀')
    expect(payload.text).toContain('[[AMW:')
  })

  it('emits a choice frame for a choice-window segment', async () => {
    const text = '请选择\n[[AMW:{"mood":"happy","sprite":"talk","voice":"soft","sfx":"bell","bgm":"none","window":"choice","windowTitle":"选择路径","choiceId":"cq_1","options":["A","B"]}]]'
    const hub = new AmadeusStreamHub(framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { text } } } }))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const payload = JSON.parse(written[0]!) as { type: string; choiceId: string; question: string; options: Array<{ label: string }> }
    expect(payload.type).toBe('choice')
    expect(payload.choiceId).toBe('cq_1')
    expect(payload.question).toBe('选择路径')
    expect(payload.options).toEqual([{ label: 'A' }, { label: 'B' }])
  })

  it('emits ended when the follow stream finishes', async () => {
    const hub = new AmadeusStreamHub(framesFor())
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const payload = JSON.parse(written[0]!) as { type: string; reason: string }
    expect(payload.type).toBe('ended')
    expect(payload.reason).toBe('stream_closed')
  })

  it('splits multi-sentence replies into separate segment frames', async () => {
    const text = '第一句\n[[AMW:{"mood":"happy","sprite":"smile","voice":"soft","sfx":"none","bgm":"none"}]]\n第二句\n[[AMW:{"mood":"think","sprite":"think","voice":"soft","sfx":"none","bgm":"none"}]]'
    const hub = new AmadeusStreamHub(framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { text } } } }))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const segments = written.map(line => JSON.parse(line) as { type: string; text: string }).filter(p => p.type === 'segments')
    expect(segments).toHaveLength(2)
    expect(segments[0]!.text).toContain('第一句')
    expect(segments[1]!.text).toContain('第二句')
  })
})