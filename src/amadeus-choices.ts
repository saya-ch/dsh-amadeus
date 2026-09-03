import { randomBytes, randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import type { AmadeusGatewayOptions } from './amadeus-extension.js'

/** A single user question carried by a `user-questions/request` waterfall. */
export interface AmadeusUserQuestion {
  readonly id: string
  readonly question: string
  readonly options?: Array<{ label: string; description?: string }>
  readonly multiSelect?: boolean
}

/** Payload of the `user-questions/request` waterfall dispatched by DSH. */
export interface AmadeusUserQuestionRequest {
  readonly questions: AmadeusUserQuestion[]
  readonly agent?: { readonly session?: { readonly id?: string } } | string
  readonly signal?: AbortSignal
}

/** Answer returned to the ask_user_question caller once the app resolves. */
export interface AmadeusUserQuestionAnswer {
  readonly answers: Array<{ id: string; selected: string[] }>
}

/** Pending choice awaiting the app's resolveChoice; the resolver is memory-only. */
export interface PendingChoice {
  id: string
  question: string
  options: string[]
  resolve: (answer: AmadeusUserQuestionAnswer) => void
  reject: (error: Error) => void
}

/** The ask_user_question request was cancelled before the app chose. */
export class AskUserQuestionAbortedError extends Error {
  readonly code = 'ASK_ABORTED'
  constructor() { super('ask_user_question aborted') }
}

/** Persisted choice metadata only (question/options), never resolvers. */
interface ChoiceMeta {
  id: string
  question: string
  options: string[]
}

/** Structural surface of the DSH context the choices adapter consumes. */
export interface AmadeusChoicesContext {
  on(name: string, listener: (request: AmadeusUserQuestionRequest) => unknown, options?: { global?: boolean; prepend?: boolean }): unknown
  readonly logger?: { warn(message: string): void }
}

async function readJson<T>(file: string, fallback: T): Promise<T> {
  let raw: string
  try {
    raw = await readFile(file, 'utf8')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return fallback
    throw error
  }
  try {
    return JSON.parse(raw) as T
  } catch {
    throw new Error(`corrupt json at ${file}`)
  }
}

async function writeJson(file: string, value: unknown): Promise<void> {
  await mkdir(dirname(file), { recursive: true })
  const tmp = `${file}.tmp-${randomUUID()}`
  await writeFile(tmp, JSON.stringify(value, null, 2))
  await rename(tmp, file)
}

/** Choices adapter for the Amadeus gateway; metadata persists, resolvers do not. */
export class AmadeusChoicesAdapter implements NonNullable<AmadeusGatewayOptions['choices']> {
  private readonly pending = new Map<string, PendingChoice>()
  private readonly meta = new Map<string, ChoiceMeta>()
  private readonly abortCleanups = new Map<string, () => void>()
  private readonly streams = new Map<string, (frame: object) => void>()

  constructor(private readonly ctx: AmadeusChoicesContext, private readonly dir: string) {}

  private file(): string {
    return join(this.dir, 'choices.json')
  }

  /** Register the SSE write function for one active session; returns unregister. */
  registerStream(sessionId: string, push: (frame: object) => void): () => void {
    this.streams.set(sessionId, push)
    return () => {
      if (this.streams.get(sessionId) === push) this.streams.delete(sessionId)
    }
  }

  async create(choiceId: string, question: string, options: string[]): Promise<void> {
    const record: ChoiceMeta = { id: choiceId, question, options }
    this.meta.set(choiceId, record)
    const all = await readJson<ChoiceMeta[]>(this.file(), [])
    const next = all.some(entry => entry.id === choiceId)
      ? all.map(entry => entry.id === choiceId ? record : entry)
      : [...all, record]
    await writeJson(this.file(), next)
  }

  async get(choiceId: string): Promise<{ question: string; options: string[] } | null> {
    const cached = this.meta.get(choiceId)
    if (cached !== undefined) return { question: cached.question, options: cached.options }
    const all = await readJson<ChoiceMeta[]>(this.file(), [])
    const hit = all.find(entry => entry.id === choiceId)
    return hit === undefined ? null : { question: hit.question, options: hit.options }
  }

  /** Resolve once the app picks an option; reject when the request aborts. */
  wait(choiceId: string, signal?: AbortSignal): Promise<AmadeusUserQuestionAnswer> {
    return new Promise<AmadeusUserQuestionAnswer>((resolve, reject) => {
      const meta = this.meta.get(choiceId)
      if (meta === undefined) { reject(new Error('choice-not-found')); return }
      if (signal?.aborted === true) { reject(new Error('choice-aborted')); return }
      const pending: PendingChoice = { id: choiceId, question: meta.question, options: meta.options, resolve, reject }
      this.pending.set(choiceId, pending)
      if (signal !== undefined) {
        const onAbort = (): void => {
          this.release(choiceId, pending)
          reject(new Error('choice-aborted'))
        }
        signal.addEventListener('abort', onAbort, { once: true })
        this.abortCleanups.set(choiceId, () => signal.removeEventListener('abort', onAbort))
      }
    })
  }

  async resolve(choiceId: string, selected: string): Promise<void> {
    const pending = this.pending.get(choiceId)
    if (pending === undefined) throw new Error(`choice ${choiceId} not pending`)
    const answer: AmadeusUserQuestionAnswer = { answers: [{ id: 'q1', selected: [selected] }] }
    this.release(choiceId, pending)
    pending.resolve(answer)
  }

  /** Reject a pending choice so the awaiting answerer rejects; a no-op when nothing is pending. */
  async cancel(choiceId: string): Promise<void> {
    const pending = this.pending.get(choiceId)
    if (pending === undefined) return
    this.release(choiceId, pending)
    pending.reject(new Error('choice-cancelled'))
  }

  /** Answer one user-questions/request; the app resolves it through the gateway. */
  async answerRequest(request: AmadeusUserQuestionRequest): Promise<AmadeusUserQuestionAnswer> {
    const question = request.questions[0]
    if (question === undefined) throw new Error('empty-questions')
    const choiceId = `cq_${randomBytes(4).toString('hex')}`
    const options = question.options?.map(option => option.label) ?? []
    await this.create(choiceId, question.question, options)
    this.pushChoice(choiceId, question, request.agent)
    try {
      const answer = await this.wait(choiceId, request.signal)
      return { answers: [{ id: question.id, selected: [...answer.answers[0]!.selected] }] }
    } catch (error) {
      if (request.signal?.aborted === true) throw new AskUserQuestionAbortedError()
      throw error
    }
  }

  /** Push a choice frame to the session's SSE stream, if one is registered. */
  private pushChoice(choiceId: string, question: AmadeusUserQuestion, agent: unknown): void {
    const sessionId = AmadeusChoicesAdapter.sessionIdOf(agent)
    const push = sessionId === undefined ? undefined : this.streams.get(sessionId)
    if (push !== undefined) {
      push({
        type: 'choice',
        choiceId,
        question: question.question,
        options: question.options?.map(option => ({
          label: option.label,
          ...(option.description === undefined ? {} : { description: option.description }),
        })) ?? [],
      })
      return
    }
    this.ctx.logger?.warn(`no stream registered for session ${sessionId ?? 'unknown'}; choice ${choiceId} remains pending`)
  }

  /** Derive the owning session id from the request agent (DSH Agent carries id). */
  private static sessionIdOf(agent: unknown): string | undefined {
    if (typeof agent === 'string') return agent
    if (agent === null || typeof agent !== 'object') return undefined
    // DSH `Agent` is `{ readonly id: SessionId }` — the id IS the session id.
    const record = agent as { id?: unknown; session?: { id?: unknown } }
    if (typeof record.id === 'string') return record.id
    // Tolerate a legacy `{ session: { id } }` shape if DSH ever re-wraps it.
    if (typeof record.session?.id === 'string') return record.session.id
    return undefined
  }

  /** Register the ask_user_question answerer on the `user-questions/request` waterfall. */
  install(): void {
    // prepend: 抢占 first-wins slot——DSH web answerer 先注册且认识每个请求，
    // 不 prepend 会被它吃掉（手机场景收不到提问，web 先弹窗挂起）。
    this.ctx.on('user-questions/request', (request: AmadeusUserQuestionRequest) => {
      return this.answerRequest(request)
    }, { global: true, prepend: true })
  }

  private release(choiceId: string, pending: PendingChoice): void {
    if (this.pending.get(choiceId) === pending) this.pending.delete(choiceId)
    const cleanup = this.abortCleanups.get(choiceId)
    if (cleanup !== undefined) {
      cleanup()
      this.abortCleanups.delete(choiceId)
    }
  }
}
