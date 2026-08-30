import { randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import type { AmadeusGatewayOptions } from './amadeus-extension.js'

/** A single user question carried by a `user-questions/request` waterfall. */
export interface AmadeusUserQuestion {
  readonly id: string
  readonly question: string
  readonly options?: Array<{ label: string }>
  readonly multiSelect?: boolean
}

/** Payload of the `user-questions/request` waterfall dispatched by DSH. */
export interface AmadeusUserQuestionRequest {
  readonly questions: AmadeusUserQuestion[]
  readonly agent: string
  readonly signal: AbortSignal
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
  on(name: string, listener: (request: AmadeusUserQuestionRequest) => unknown): unknown
}

async function readJson<T>(file: string, fallback: T): Promise<T> {
  try {
    return JSON.parse(await readFile(file, 'utf8')) as T
  } catch {
    return fallback
  }
}

async function writeJson(file: string, value: unknown): Promise<void> {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, JSON.stringify(value, null, 2))
}

/** Choices adapter for the Amadeus gateway; metadata persists, resolvers do not. */
export class AmadeusChoicesAdapter implements NonNullable<AmadeusGatewayOptions['choices']> {
  private readonly pending = new Map<string, PendingChoice>()
  private readonly meta = new Map<string, ChoiceMeta>()
  private readonly abortCleanups = new Map<string, () => void>()

  constructor(private readonly ctx: AmadeusChoicesContext, private readonly dir: string) {}

  private file(): string {
    return join(this.dir, 'choices.json')
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

  /** Answer one user-questions/request; the app resolves it through the gateway. */
  async answerRequest(request: AmadeusUserQuestionRequest): Promise<AmadeusUserQuestionAnswer> {
    const question = request.questions[0]
    if (question === undefined) throw new Error('empty-questions')
    const choiceId = `cq_${randomBytes(4).toString('hex')}`
    const options = question.options?.map(option => option.label) ?? []
    await this.create(choiceId, question.question, options)
    try {
      const answer = await this.wait(choiceId, request.signal)
      return { answers: [{ id: question.id, selected: [...answer.answers[0]!.selected] }] }
    } catch (error) {
      if (request.signal?.aborted === true) throw new AskUserQuestionAbortedError()
      throw error
    }
  }

  /** Register the ask_user_question answerer on the `user-questions/request` waterfall. */
  install(): void {
    this.ctx.on('user-questions/request', (request: AmadeusUserQuestionRequest) => {
      return this.answerRequest(request)
    })
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