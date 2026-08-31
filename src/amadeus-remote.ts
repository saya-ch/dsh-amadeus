import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

/** Remote transports selectable by the Amadeus host. */
export type RemoteProvider = 'tailscale' | 'cpolar' | 'frp'

/** Safe status reported by every remote provider controller. */
export interface AmadeusRemoteStatus {
  enabled: boolean
  state: string
  origin?: string
  errorCode?: string
}

/** Lifecycle shared by selectable remote providers. */
export interface AmadeusRemoteController {
  initialize(): Promise<void>
  status(): AmadeusRemoteStatus
  setEnabled(on: boolean): Promise<void>
  close(): Promise<void>
}

/** Durable single-provider selection for the Amadeus host. */
export class JsonRemoteStore {
  constructor(private readonly file: string, private readonly defaultProvider: RemoteProvider) {}

  async load(): Promise<RemoteProvider> {
    try {
      return (JSON.parse(await readFile(this.file, 'utf8')) as { provider: RemoteProvider }).provider
    } catch {
      return this.defaultProvider
    }
  }

  async save(provider: RemoteProvider): Promise<void> {
    await mkdir(dirname(this.file), { recursive: true })
    await writeFile(this.file, JSON.stringify({ version: 1, provider }))
  }
}

/** Serialize switches and preserve the single-provider invariant. */
export class AmadeusRemoteCoordinator {
  private selectedValue: RemoteProvider
  private queue: Promise<void> = Promise.resolve()

  constructor(
    private readonly controllers: Record<RemoteProvider, AmadeusRemoteController>,
    private readonly store: JsonRemoteStore,
    defaultProvider: RemoteProvider,
  ) {
    this.selectedValue = defaultProvider
  }

  get selected(): RemoteProvider {
    return this.selectedValue
  }

  /** Restore the persisted provider and initialize only its controller. */
  async initialize(): Promise<void> {
    this.selectedValue = await this.store.load()
    await this.controllers[this.selectedValue].initialize()
  }

  /** Disable the previous provider, persist the new selection, and swap. */
  select(provider: RemoteProvider): Promise<void> {
    const run = async () => {
      if (provider === this.selectedValue) return
      const previous = this.controllers[this.selectedValue]
      if (previous.status().enabled) await previous.setEnabled(false)
      await this.store.save(provider)
      this.selectedValue = provider
    }
    const task = this.queue.then(run, run)
    this.queue = task.then(() => undefined, () => undefined)
    return task
  }

  status(): AmadeusRemoteStatus {
    return this.controllers[this.selectedValue].status()
  }

  async close(): Promise<void> {
    for (const controller of Object.values(this.controllers)) await controller.close()
  }
}

/** FRP-first controller skeleton; frpc process wiring is deferred to a later iteration. */
export class FrpController implements AmadeusRemoteController {
  private enabled = false
  private originValue: string | undefined

  constructor(private readonly dir: string, private readonly configFile: string) {}

  /** Prepare the frpc launch context without auto-starting the community frpc binary. */
  async initialize(): Promise<void> {
    // TODO(frpc): 读取 ~/.dsh/amadeus/frp.json {serverAddress, port, token, publicOrigin}，
    // 存在则准备启动（不自动启动）。
  }

  status(): AmadeusRemoteStatus {
    if (!this.enabled) return { enabled: false, state: 'off' }
    return {
      enabled: true,
      state: 'ready',
      ...(this.originValue === undefined ? {} : { origin: this.originValue }),
    }
  }

  async setEnabled(on: boolean): Promise<void> {
    this.enabled = on
    // TODO(frpc): on=true 时由 frpc 子进程的真实公网 origin 填充 originValue；骨架不伪造端点。
  }

  async close(): Promise<void> {
    // TODO(frpc): 真实 wiring 在 close() 时停止 frpc 子进程并清理运行时配置。
    this.enabled = false
  }
}