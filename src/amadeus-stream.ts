import { parseAmadeusSegments, type AmadeusSegment } from './amadeus-tags.js'

/** One event carried by a DSH session follow frame. */
export interface AmadeusSessionFollowEvent {
  readonly type: string
  readonly data?: unknown
}

/** A frame yielded by `sessionController.follow()` for a session address. */
export interface AmadeusSessionFollowFrame {
  readonly type: 'snapshot' | 'event'
  readonly event?: AmadeusSessionFollowEvent
}

/** Structural surface of the DSH session follow stream the hub consumes. */
export interface AmadeusStreamContext {
  readonly sessionController: {
    follow(req: { address: { kind: 'session'; sessionId: string } }): AsyncIterable<AmadeusSessionFollowFrame>
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
 *   - a segment whose tag opens a `choice` window is emitted as a `choice` frame
 *     (choiceId/question/options) instead, matching the App contract.
 *   - the stream finishes with an `ended` frame when the session ends or the
 *     follow iteration completes.
 * The returned handle closes the pump; `onFinished` fires once the pump has
 * naturally drained (used by the SSE route to end its response stream).
 */
export class AmadeusStreamHub {
  constructor(private readonly ctx: AmadeusStreamContext) {}

  async open(sessionId: string, write: (data: string) => void, onFinished?: () => void): Promise<() => void> {
    let closed = false
    let finished = false
    const frames = this.ctx.sessionController.follow({ address: { kind: 'session', sessionId } })
    const finish = (reason: string): void => {
      if (finished || closed) return
      finished = true
      write(JSON.stringify({ type: 'ended', reason }))
      onFinished?.()
    }
    const pump = (async () => {
      try {
        for await (const frame of frames) {
          if (closed) break
          if (frame.type !== 'event' || frame.event === undefined) continue
          const event = frame.event
          if (event.type === 'assistant/message') {
            const text = (event.data as { message?: { text?: string } } | undefined)?.message?.text
            if (typeof text === 'string' && text.length > 0) this.emit(text, write)
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
    return () => { closed = true }
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