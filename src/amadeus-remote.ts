import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { spawn as nodeSpawn } from 'node:child_process'
import type { SpawnOptions } from 'node:child_process'
import { createConnection } from 'node:net'

/** Remote transports selectable by the Amadeus host. */
export type RemoteProvider = 'tailscale' | 'cpolar' | 'frp'

/** Safe status reported by every remote provider controller. */
export interface AmadeusRemoteStatus {
  enabled: boolean
  state: string
  origin?: string
  errorCode?: string
  pid?: number
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

/** FrpRuntime configuration loaded from the frp.json config file. */
export interface FrpRuntime {
  serverAddress: string
  serverPort: number
  token?: string
  remotePort?: number
  publicOrigin?: string
}

/** Validate and normalize the raw JSON contents of an frp.json config. */
export function parseFrpConfig(raw: unknown): FrpRuntime {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new Error('frp config must be an object')
  const value = raw as Record<string, unknown>
  if (typeof value.serverAddress !== 'string' || value.serverAddress.length === 0) {
    throw new Error('frp config requires serverAddress')
  }
  const serverPort = value.serverPort
  if (typeof serverPort !== 'number' || !Number.isInteger(serverPort) || serverPort < 1 || serverPort > 65535) {
    throw new Error('frp config requires serverPort in 1..65535')
  }
  return {
    serverAddress: value.serverAddress,
    serverPort,
    ...(typeof value.token === 'string' ? { token: value.token } : {}),
    ...(typeof value.remotePort === 'number' ? { remotePort: value.remotePort } : {}),
    ...(typeof value.publicOrigin === 'string' ? { publicOrigin: value.publicOrigin } : {}),
  }
}

/** Minimal child-process handle surfaced to FrpController (injectable in tests). */
export interface SpawnHandle {
  on(event: string, cb: (code?: number | null, signal?: string | null) => void): unknown
  kill(signal?: string): boolean
  pid?: number
}

export type SpawnFn = (command: string, args: string[], options: object) => SpawnHandle

export interface FrpControllerOptions {
  command?: string
  spawn?: SpawnFn
  reachabilityTimeoutMs?: number
}

/** FRP process controller: writes a runtime config, spawns frpc, and probes the public endpoint. */
export class FrpController implements AmadeusRemoteController {
  private enabled = false
  private config: FrpRuntime | undefined
  private child: SpawnHandle | undefined
  private originValue: string | undefined
  private errorCodeValue: string | undefined
  private pidValue: number | undefined
  private readonly command: string
  private readonly spawnFn: SpawnFn
  private readonly reachabilityTimeoutMs: number

  constructor(private readonly dir: string, private readonly configFile: string, options: FrpControllerOptions = {}) {
    this.command = options.command ?? 'frpc'
    this.spawnFn = options.spawn ?? ((command, args, opts) => nodeSpawn(command, args, opts as SpawnOptions) as unknown as SpawnHandle)
    this.reachabilityTimeoutMs = options.reachabilityTimeoutMs ?? 3000
  }

  /** Read and validate the frp.json config; ENOENT leaves the controller unconfigured. */
  async initialize(): Promise<void> {
    this.enabled = false
    this.config = undefined
    this.originValue = undefined
    this.errorCodeValue = undefined
    try {
      this.config = parseFrpConfig(JSON.parse(await readFile(this.configFile, 'utf8')) as unknown)
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'ENOENT') this.errorCodeValue = 'invalid_frp_config'
    }
  }

  status(): AmadeusRemoteStatus {
    if (this.config === undefined) {
      return {
        enabled: false,
        state: 'unconfigured',
        ...(this.errorCodeValue === undefined ? {} : { errorCode: this.errorCodeValue }),
      }
    }
    if (!this.enabled) return { enabled: false, state: 'off' }
    if (this.child === undefined || this.pidValue === undefined) {
      return {
        enabled: false,
        state: 'failed',
        ...(this.errorCodeValue === undefined ? {} : { errorCode: this.errorCodeValue }),
      }
    }
    return {
      enabled: true,
      state: 'running',
      ...(this.pidValue === undefined ? {} : { pid: this.pidValue }),
      ...(this.originValue === undefined ? {} : { origin: this.originValue }),
      ...(this.errorCodeValue === undefined ? {} : { errorCode: this.errorCodeValue }),
    }
  }

  /** Spawn (on) or kill (off) the frpc child process. */
  async setEnabled(on: boolean): Promise<void> {
    if (!on) {
      this.child?.kill('SIGTERM')
      this.child = undefined
      this.pidValue = undefined
      this.enabled = false
      this.originValue = undefined
      this.errorCodeValue = undefined
      return
    }
    if (this.enabled) return
    if (this.config === undefined) throw new Error('cannot enable frp without a configured frp.json')
    const configPath = join(this.dir, 'frpc-run.toml')
    await mkdir(this.dir, { recursive: true })
    await writeFile(configPath, this.renderConfig(this.config))
    const child = this.spawnFn(this.command, ['-c', configPath], { stdio: ['ignore', 'pipe', 'pipe'] })
    this.child = child
    this.pidValue = child.pid
    this.enabled = true
    this.originValue = undefined
    this.errorCodeValue = undefined
    child.on('exit', (code) => {
      if (this.child !== child) return
      this.pidValue = undefined
      this.errorCodeValue = code === 0 ? undefined : `frpc_exit_${code === undefined || code === null ? 'signal' : code}`
    })
    child.on('error', () => {
      if (this.child !== child) return
      this.pidValue = undefined
      if (this.errorCodeValue === undefined) this.errorCodeValue = 'frpc_spawn_error'
    })
    if (this.config.publicOrigin !== undefined) this.probePublicOrigin(this.config.publicOrigin)
  }

  /** Terminate the child process and clean up the runtime config file. */
  async close(): Promise<void> {
    this.child?.kill('SIGTERM')
    this.child = undefined
    this.pidValue = undefined
    this.enabled = false
    this.originValue = undefined
    this.errorCodeValue = undefined
    await rm(join(this.dir, 'frpc-run.toml'), { force: true })
  }

  /** Render an frpc TOML runtime config mirroring the validated FrpRuntime. */
  private renderConfig(config: FrpRuntime): string {
    const lines = [
      `serverAddr = "${config.serverAddress}"`,
      `serverPort = ${config.serverPort}`,
    ]
    if (config.token !== undefined) lines.push(`auth.token = "${config.token}"`)
    lines.push(
      '',
      '[[proxies]]',
      'name = "amadeus-whale"',
      'type = "tcp"',
      'localIP = "127.0.0.1"',
      'localPort = 3444',
      `remotePort = ${config.remotePort ?? config.serverPort}`,
    )
    return `${lines.join('\n')}\n`
  }

  /** Fill originValue only when the publicOrigin endpoint accepts a TCP connection. */
  private probePublicOrigin(origin: string): void {
    try {
      const parsed = new URL(origin)
      const socket = createConnection({ host: parsed.hostname, port: parsed.port === '' ? 443 : Number(parsed.port) })
      socket.unref()
      const fail = (): void => {
        socket.destroy()
        if (this.errorCodeValue === undefined) this.errorCodeValue = 'endpoint_unreachable'
      }
      socket.setTimeout(this.reachabilityTimeoutMs, fail)
      socket.once('connect', () => {
        socket.destroy()
        this.originValue = origin
      })
      socket.once('error', fail)
    } catch {
      if (this.errorCodeValue === undefined) this.errorCodeValue = 'endpoint_unreachable'
    }
  }
}

/** Placeholder controller for providers not yet wired (tailscale/cpolar). */
export class NoopRemoteController implements AmadeusRemoteController {
  async initialize(): Promise<void> {
    // no-op
  }

  status(): AmadeusRemoteStatus {
    return { enabled: false, state: 'off' }
  }

  async setEnabled(on: boolean): Promise<void> {
    throw new Error('unsupported provider: remote disabled')
  }

  async close(): Promise<void> {
    // no-op
  }
}
