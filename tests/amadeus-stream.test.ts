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

function controllableFollow(initial: any[] = []) {
  let returned = false
  let returnCalls = 0
  let pending: ((r: IteratorResult<any>) => void) | undefined
  const frames = [...initial]
  const iterator = {
    [Symbol.asyncIterator]() { return this },
    next: async (): Promise<IteratorResult<any>> => {
      if (returned) return { done: true, value: undefined }
      if (frames.length > 0) return { done: false, value: frames.shift() }
      return new Promise<IteratorResult<any>>(resolve => { pending = resolve })
    },
    return: async (): Promise<IteratorResult<any>> => {
      returned = true
      returnCalls++
      pending?.({ done: true, value: undefined })
      pending = undefined
      return { done: true, value: undefined }
    },
  }
  return {
    iterator,
    push: (frame: any) => { frames.push(frame) },
    get returnCalls() { return returnCalls },
    get returned() { return returned },
  }
}

describe('stream hub', () => {
  it('emits a dialogue frame from a follow event (protocol simplified, 3.8/3.18)', async () => {
    const frames = [
      { type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '好呀\n[[AMW:{"mood":"happy","sprite":"wag","voice":"soft","sfx":"none","bgm":"none"}]]' }] } } } },
    ]
    const hub = new AmadeusStreamHub(framesFor(...frames))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const payload = JSON.parse(written[0]!) as { type: string; text: string; tag?: Record<string, unknown> }
    expect(payload.type).toBe('dialogue')
    expect(payload.text).toBe('好呀')
    expect(payload.tag?.mood).toBe('happy')
    expect(payload.tag?.sprite).toBe('wag')
  })

  it('emits a choice frame for a choice-window segment', async () => {
    const text = '请选择\n[[AMW:{"mood":"happy","sprite":"talk","voice":"soft","sfx":"bell","bgm":"none","window":"choice","windowTitle":"选择路径","choiceId":"cq_1","options":["A","B"]}]]'
    const hub = new AmadeusStreamHub(framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text }] } } } }))
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

  it('treats a whole multi-sentence reply as ONE dialogue frame (no per-sentence split, 3.8)', async () => {
    const text = '第一句\n第二句\n[[AMW:{"mood":"happy","sprite":"smile","voice":"soft","sfx":"none","bgm":"none"}]]'
    const hub = new AmadeusStreamHub(framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text }] } } } }))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const frames = written.map(line => JSON.parse(line) as { type: string; text?: string }).filter(p => p.type === 'dialogue')
    expect(frames).toHaveLength(1)
    expect(frames[0]!.text).toContain('第一句')
    expect(frames[0]!.text).toContain('第二句')
  })

  it('intercepts overlong text: truncates to 1-2 sentences + report window (long-text rule, 3.18)', async () => {
    const long = '这是第一句。这是第二句。这是第三句。这是第四句。这是第五句。这一段已经超过四个句子所以触发长文本拦截。'
    let savedReport: { id: string; title: string; markdown: string } | undefined
    const ctx = framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: `${long}\n[[AMW:{"mood":"idle","sprite":"smile"}]]` }] } } } })
    ctx.reports = { save: async (r: any) => { savedReport = r } }
    const hub = new AmadeusStreamHub(ctx)
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const dialogueFrames = written.map(line => JSON.parse(line) as { type: string; text?: string; tag?: any }).filter(p => p.type === 'dialogue')
    // 2 dialogue frames: truncated summary + report hint
    expect(dialogueFrames).toHaveLength(2)
    expect(dialogueFrames[0]!.text!.length).toBeLessThanOrEqual(60)
    expect(dialogueFrames[0]!.text).toContain('…')
    expect(dialogueFrames[1]!.tag?.window).toBe('report')
    expect(savedReport).toBeDefined()
    expect(savedReport!.markdown).toBe(long)
  })

  it('does not intercept a normal-length reply', async () => {
    const text = '呜... 月光照在礁石上呢...\n[[AMW:{"mood":"happy","sprite":"wag"}]]'
    const hub = new AmadeusStreamHub(framesFor({ type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text }] } } } }))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    const frames = written.map(line => JSON.parse(line) as { type: string; text?: string }).filter(p => p.type === 'dialogue')
    expect(frames).toHaveLength(1)
    expect(frames[0]!.text).toBe('呜... 月光照在礁石上呢...')
  })

  it('close terminates the follow subscription and is idempotent', async () => {
    const follow = controllableFollow()
    const hub = new AmadeusStreamHub({ sessionController: { follow: () => follow.iterator } })
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    expect(follow.returnCalls).toBe(0)
    await close()
    await close()
    expect(follow.returnCalls).toBe(1)
    expect(follow.returned).toBe(true)
    await flush()
    expect(written).toEqual([])
    follow.push({ type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '好呀' }] } } } })
    await flush()
    expect(written).toEqual([])
  })

  it('does not fold snapshot records into segments (page is the authoritative latest state)', async () => {
    const snapshot = {
      type: 'snapshot',
      records: [
        { type: 'event', event: { type: 'user/message', data: { content: [{ type: 'text', text: '你好' }] } } },
        { type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '早呀\n[[AMW:{"mood":"happy","sprite":"wag","voice":"soft","sfx":"none","bgm":"none"}]]' }] } } } },
        { type: 'event', event: { type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '最新\n[[AMW:{"mood":"happy","sprite":"smile","voice":"soft","sfx":"none","bgm":"none"}]]' }] } } } },
      ],
    }
    const hub = new AmadeusStreamHub(framesFor(snapshot))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    expect(written.map(line => (JSON.parse(line) as { type: string }).type)).toEqual(['ended'])
  })

  it('ignores a snapshot with no assistant records', async () => {
    const snapshot = { type: 'snapshot', records: [{ type: 'event', event: { type: 'user/message', data: { content: [{ type: 'text', text: '你好' }] } } }] }
    const hub = new AmadeusStreamHub(framesFor(snapshot))
    const written: string[] = []
    const close = await hub.open('s1', d => written.push(d))
    await flush()
    await close()
    expect(written.map(line => (JSON.parse(line) as { type: string }).type)).toEqual(['ended'])
  })
})