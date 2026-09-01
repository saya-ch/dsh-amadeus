import { parseAmadeusSegments, type AmadeusSegment } from './amadeus-tags.js'
import { assistantMessageText } from './amadeus-text.js'

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
}

const ENDED_REASON_STREAM = 'stream_closed'
const ENDED_REASON_SESSION = 'session_ended'

/**
 * Bridges a DSH session follow stream to the App SSE contract.
 *
 * `open` pumps the follow stream and writes one `data:` payload per frame:
 *   - assistant/message text is re-parsed into segments (`句\n[[AMW:...]]`), one
 *     `segments` frame per sentence, preserving the original tagged raw text.
 *   - snapshot records are deliberately NOT folded: the App already loaded the
 *     full history (and its latest state) via `page` before opening the stream,
 *     so folding them would duplicate the current screen.
 *   - a segment whose tag opens a `choice` window is emitted as a `choice` frame
 *     (choiceId/question/options) instead, matching the App contract.
 *   - the stream finishes with an `ended` frame when the session ends or the
 *     follow iteration completes.
 * The returned handle closes the pump: it terminates the underlying follow
 * subscription via `iterator.return()` (so a disconnected SSE client does not
 * leave the DSH subscription alive) and is idempotent. `onFinished` fires once
 * the pump has naturally drained (used by the SSE route to end its response
 * stream); closing the pump suppresses the `ended` frame.
 */
export class AmadeusStreamHub {
  constructor(private readonly ctx: AmadeusStreamContext) {}

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
            this.emitText(event, write)
          } else if (event.type === 'session/end') {
            finish(ENDED_REASON_SESSION)
            break
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

  private emitText(event: AmadeusSessionFollowEvent, write: (data: string) => void): void {
    const text = assistantMessageText(event.data)
    if (text.length > 0) this.emit(text, write)
  }

  private emit(text: string, write: (data: string) => void): void {
    const segments = parseAmadeusSegments(text)
    for (const segment of segments) {
      write(JSON.stringify(this.payload(segment)))
    }
  }

  private payload(segment: AmadeusSegment): Record<string, unknown> {
    const tag = segment.tag
    if (tag?.window === 'choice' && typeof tag.choiceId === 'string' && (tag.options?.length ?? 0) > 0) {
      return {
        type: 'choice',
        choiceId: tag.choiceId,
        question: tag.windowTitle ?? segment.clean,
        options: tag.options!.map(label => ({ label })),
      }
    }
    return { type: 'segments', text: segment.raw }
  }
}