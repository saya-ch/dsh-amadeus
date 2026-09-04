import { parseAmadeusTag, stripAmadeusTag, type AmadeusTag } from './amadeus-tags.js'
import { assistantMessageText } from './amadeus-text.js'
import { randomUUID } from 'node:crypto'

/** One event carried by a DSH session follow frame or snapshot record. */
export interface AmadeusSessionFollowEvent {
  readonly type: string
  readonly data?: unknown
}

/** A history record folded into a snapshot follow frame. */
export interface AmadeusSessionSnapshotRecord {
  readonly type: 'event'
  readonly event: AmadeusSessionFollowEvent
}

/** A frame yielded by `sessionController.follow()` for a session address. */
export interface AmadeusSessionFollowFrame {
  readonly type: 'snapshot' | 'event'
  readonly event?: AmadeusSessionFollowEvent
  readonly records?: ReadonlyArray<AmadeusSessionSnapshotRecord>
}

/** Structural surface of the DSH session follow stream the hub consumes. */
export interface AmadeusStreamContext {
  readonly sessionController: {
    follow(req: { address: { kind: 'session'; sessionId: string } }, signal?: AbortSignal): AsyncIterable<AmadeusSessionFollowFrame>
  }
  /** Optional report persistence for the long-text interceptor (3.18). */
  readonly reports?: {
    save(report: { id: string; title: string; markdown: string }): Promise<void>
  }
}

const ENDED_REASON_STREAM = 'stream_closed'
const ENDED_REASON_SESSION = 'session_ended'

/** 长文本铁律判定（架构 3.18）：len > 120 字符 或 句数 > 4 → 超长。 */
export function isOverlong(text: string): boolean {
  if (text.length > 120) return true
  const sentences = text.split(/[。！？!?；;\n]/u).filter(s => s.trim().length > 0)
  return sentences.length > 4
}

/** 截断保留前 1~2 句 + "…"（架构 3.18 兜底）。 */
export function truncateForDialogue(text: string): string {
  const sentences = text.split(/(?<=[。！？!?；;\n])/u).map(s => s.trim()).filter(Boolean)
  const kept = sentences.slice(0, 2).join('').trim()
  return kept.length >= text.length ? text : `${kept}…`
}

/**
 * Bridges a DSH session follow stream to the App SSE contract (3.19).
 *
 * `open` pumps the follow stream and writes one `data:` payload per frame:
 *   - assistant/message text is ONE `dialogue` frame (protocol simplification,
 *     3.8/3.18): the whole message is a single display, tag parsed from the
 *     segment-tail [[AMW:...]] and sent as a `tag` object.
 *   - overlong text (isOverlong) is intercepted (3.18 long-text rule): the
 *     dialogue keeps the first 1~2 sentences + "…", and the FULL text is saved
 *     as a report (window=report frame follows), without calling the model.
 *   - snapshot records are deliberately NOT folded: the App already loaded the
 *     full history (and its latest state) via `page` before opening the stream.
 *   - a tag whose window is `choice` is emitted as a `choice` frame
 *     (choiceId/question/options) instead, matching the App contract.
 *   - the stream finishes with an `ended` frame when the session ends or the
 *     follow iteration completes.
 * The returned handle closes the pump. `onFinished` fires once the pump has
 * naturally drained; closing the pump suppresses the `ended` frame.
 */
export class AmadeusStreamHub {
  constructor(private readonly ctx: AmadeusStreamContext) {}

  /** 最近一次幕后活动（工具/步骤）时间戳：无标签对话据此推断“干活中”表情。 */
  private recentWorkAt = 0

  async open(sessionId: string, write: (data: string) => void, onFinished?: () => void): Promise<() => void> {
    let closed = false
    let finished = false
    const abort = new AbortController()
    const frames = this.ctx.sessionController.follow({ address: { kind: 'session', sessionId } }, abort.signal)
    let iterator: AsyncIterator<AmadeusSessionFollowFrame> | undefined = frames[Symbol.asyncIterator]()
    const finish = (reason: string): void => {
      if (finished || closed) return
      finished = true
      write(JSON.stringify({ type: 'ended', reason }))
      onFinished?.()
    }
    const pump = (async () => {
      try {
        for (;;) {
          const next = await iterator.next()
          if (closed || next.done) break
          const frame = next.value
          if (frame.type === 'snapshot') {
            // The App already loaded full history via `page`; folding the
            // snapshot's records here would duplicate the current state.
            continue
          }
          if (frame.type !== 'event' || frame.event === undefined) continue
          const event = frame.event
          if (event.type === 'assistant/message') {
            await this.emitText(event, write)
          } else if (event.type === 'session/end') {
            finish(ENDED_REASON_SESSION)
            break
          } else if (event.type === 'turn/end') {
            // 回合结束信号：App 据此清空事件流（浮字渐隐）并去掉末句工作符号
            const data = event.data as Record<string, unknown> | undefined
            const reason = data?.reason as Record<string, unknown> | undefined
            write(JSON.stringify({ type: 'turn', status: 'end', reason: String(reason?.kind ?? data?.reason ?? '') }))
          } else {
            // 幕后事件（思考/工具/step/turn 等）→ activity 帧（产品 1.5.1/3.19）
            const activity = this.toActivity(event)
            if (activity !== null) {
              this.recentWorkAt = Date.now()
              write(JSON.stringify(activity))
            }
          }
        }
      } catch {
        // The follow stream closed unexpectedly; finish below.
      }
      finish(ENDED_REASON_STREAM)
    })()
    return () => {
      if (closed) return
      closed = true
      abort.abort()
      const iter = iterator
      iterator = undefined
      void iter?.return?.()
    }
  }

  private async emitText(event: AmadeusSessionFollowEvent, write: (data: string) => void): Promise<void> {
    const text = assistantMessageText(event.data)
    if (text.length > 0) await this.emit(text, write)
  }

  /** 幕后事件 → activity 帧（产品 1.5.1：思考/工具/step 进事件流小窗）。 */
  private toActivity(event: AmadeusSessionFollowEvent): Record<string, unknown> | null {
    const type = event.type
    const data = event.data as Record<string, unknown> | undefined
    if (type === 'tool/call') {
      const name = String(data?.name ?? 'tool')
      const args = String(data?.arguments ?? '')
      return { type: 'activity', kind: 'tool', title: `调用 ${name}`, detail: args.slice(0, 200) }
    }
    if (type === 'tool/result') {
      const name = String(data?.name ?? '')
      const err = data?.error !== undefined ? '（失败）' : ''
      return { type: 'activity', kind: 'tool', title: `${name} 完成${err}`, detail: '' }
    }
    if (type === 'step/start') {
      return { type: 'activity', kind: 'step', title: `步骤 ${String(data?.step ?? '')}`, detail: '' }
    }
    if (type === 'turn/start') {
      return { type: 'activity', kind: 'turn', title: `回合 ${String(data?.turn ?? '')} 开始`, detail: '' }
    }
    if (type === 'approval/asked') {
      return { type: 'activity', kind: 'approval', title: '等待批准', detail: String(data?.reason ?? '') }
    }
    if (type === 'compaction/start') {
      return { type: 'activity', kind: 'step', title: '压缩上下文', detail: '' }
    }
    if (type === 'todo/write') {
      const todos = (data?.todos as Array<{ label?: string }> | undefined) ?? []
      return { type: 'activity', kind: 'step', title: `待办 ${todos.length} 项`, detail: todos.slice(0, 3).map(t => t.label ?? '').join('、') }
    }
    return null
  }

  private async emit(text: string, write: (data: string) => void): Promise<void> {
    const parsed = parseAmadeusTag(text)
    const tag = parsed.tag
    if (tag?.window === 'choice' && typeof tag.choiceId === 'string' && (tag.options?.length ?? 0) > 0) {
      write(JSON.stringify({
        type: 'choice',
        choiceId: tag.choiceId,
        question: tag.windowTitle ?? parsed.clean,
        options: tag.options!.map(label => ({ label })),
      }))
      return
    }
    let clean = parsed.clean
    if (isOverlong(clean)) {
      // 长文本铁律兜底（3.18）：截断前 1~2 句进对话框，全文存报告，不调模型
      const truncated = truncateForDialogue(clean)
      const reportId = `amw-${randomUUID().slice(0, 8)}`
      await this.ctx.reports?.save({
        id: reportId,
        title: '长文本内容',
        markdown: clean,
      })
      write(JSON.stringify({
        type: 'dialogue',
        text: truncated,
        tag: this.tagOrFallback(tag),
        working: Date.now() - this.recentWorkAt < 12_000,
      }))
      write(JSON.stringify({
        type: 'dialogue',
        text: '详细内容我放进小窗口里啦 啾~',
        tag: this.tagWithWindow(tag, 'report', reportId, '长文本内容'),
        working: false,
      }))
      // 报告正文内联推送（App 报告入口就靠这帧；reports.save 只落盘，App 拿不到）
      write(JSON.stringify({
        type: 'report',
        reportId,
        title: '长文本内容',
        body: clean,
      }))
      return
    }
    write(JSON.stringify({
      type: 'dialogue',
      text: clean,
      tag: this.tagOrFallback(tag),
      // 回合工作中：刚有工具/步骤活动时说的话 = 任务中的旁白（句末带工作符号）；
      // 无活动的单答 = 最终回答（不带符号）。
      working: Date.now() - this.recentWorkAt < 12_000,
    }))
  }

  private tagOrFallback(tag: AmadeusTag | null): Record<string, unknown> {
    if (tag !== null) return { ...tag }
    // 无标签文本：刚有工具/步骤活动 → 干活中的专注脸（thinking）；否则 normal
    if (Date.now() - this.recentWorkAt < 12_000) {
      return { sprite: 'thinking', voice: 'soft', sfx: 'none', bgm: 'none' }
    }
    return { sprite: 'normal', voice: 'soft', sfx: 'none', bgm: 'none' }
  }

  private tagWithWindow(tag: AmadeusTag | null, window: string, windowId: string, windowTitle: string): Record<string, unknown> {
    const base = tag === null ? {} : { ...tag }
    return { ...base, window, windowId, windowTitle }
  }
}
