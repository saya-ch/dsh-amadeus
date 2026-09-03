import { homedir } from 'node:os'
import { join, isAbsolute, resolve } from 'node:path'
import { X509Certificate } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import type { Context } from '@deepseek-ai/cordis'
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import { MobileAccessGateway } from './gateway.js'
import { createMobileAccessService } from './extensions.js'
import { createAmadeusExtension, type AmadeusGatewayOptions, type AmadeusSessionSummary, AmadeusRequestError } from './amadeus-extension.js'
import { AMADEUS_MODE_ID } from './amadeus-mode.js'
import { AmadeusSessionCommands, AmadeusSessionsAdapter, type AmadeusSessionsContext } from './amadeus-sessions.js'
import { AmadeusSessionRegistry, amadeusSessionRegistryFile } from './amadeus-session-registry.js'
import { AmadeusPreviewStore, AmadeusReportsAdapter } from './amadeus-reports.js'
import { AmadeusApprovalAdapter, type AmadeusApprovalContext } from './amadeus-approval.js'
import { AmadeusChoicesAdapter, type AmadeusChoicesContext } from './amadeus-choices.js'
import { AmadeusStreamHub } from './amadeus-stream.js'
import { registerAmadeusTools } from './amadeus-tools.js'
import { AmadeusControlRoutes } from './amadeus-control.js'
import {
  amadeusCpolarStateDirectory,
  amadeusDefaultRemoteProvider,
  amadeusFrpConfigDirectory,
  amadeusInstanceId,
  amadeusRemoteControlFiles,
  amadeusRemoteDeviceFile,
  amadeusRemoteDirectory,
  amadeusRemoteGatewayConfig,
  amadeusRemoteProviderFile,
  amadeusTailscaleStateDirectory,
  CpolarComponentManager,
  CpolarController,
  FrpComponentManager,
  FrpConfigStore,
  FrpController,
  FunnelController,
  funnelExecutable,
  JsonRemoteProviderStore,
  RemoteProviderCoordinator,
  type RemoteProvider,
  type RemoteProviderController,
  type RemoteProviderStatus,
} from './amadeus-remote.js'
import { JsonDeviceStore } from './storage.js'
import type { PairingWindow } from './access.js'
import { JsonMobileAccessControlStore, MobileAccessGatewayController, type MobileAccessRuntime } from './control.js'
import { parseControlFile, parseGatewayConfig, type PluginConfig, type ResolvedGatewayConfig } from './config.js'
import { FollowingMobileAccessRuntime } from './control.js'
import { materializeManagedSetup, parseManagedSetup, type ManagedSetup } from './managed-setup.js'
import { assertLocalAdminTrust, HttpError, LOCAL_ADMIN_PREFIX, parseRequestTarget, readJsonObject, sendFailure, sendJson } from './http-security.js'
import { Config as GatewayConfigSchema } from './config.js'

/** Stable Cordis plugin name. Fully independent from dsh-mobile. */
export const name = 'dsh-amadeus'

/** Own webserver route + loopback DSH connection; no shared services with dsh-mobile. */
export const inject = ['webServer', 'connection', 'tools', 'sessionQuery', 'sessionController', 'workspaceRegistry']

/** Amadeus configuration mirrors the gateway needs: its own files, port and TLS. */
export const Config = GatewayConfigSchema

/** Ensure the Amadeus user preset exists so the desktop roster can select it. */
async function ensureAmadeusPreset(): Promise<void> {
  const { homedir } = await import('node:os')
  const { mkdir, copyFile, stat } = await import('node:fs/promises')
  const { join } = await import('node:path')
  const { fileURLToPath } = await import('node:url')
  const presetDir = join(homedir(), '.dsh', '.agent-presets', 'amadeus')
  try {
    await stat(join(presetDir, 'agent.cordis.yml'))
    return
  } catch { /* fall through to install */ }
  const bundled = fileURLToPath(new URL('../presets/amadeus', import.meta.url))
  await mkdir(presetDir, { recursive: true })
  await copyFile(join(bundled, 'preset.yml'), join(presetDir, 'preset.yml'))
  await copyFile(join(bundled, 'agent.cordis.yml'), join(presetDir, 'agent.cordis.yml'))
}

interface BrowserAuthenticatedConnection {
  authenticatedUrl?: (baseUrl: string) => string
}

function upstreamAuthenticatedUrl(ctx: Context, upstreamOrigin: URL): string | undefined {
  const connection = (ctx as Context & { readonly connection?: BrowserAuthenticatedConnection }).connection
  return typeof connection?.authenticatedUrl === 'function'
    ? connection.authenticatedUrl(upstreamOrigin.origin)
    : undefined
}

function mapAdminError(error: unknown): HttpError {
  if (error instanceof HttpError) return error
  const code = (error as NodeJS.ErrnoException).code
  if (code === 'EADDRNOTAVAIL') return new HttpError(409, 'network_address_changed')
  if (code === 'EADDRINUSE') return new HttpError(409, 'listen_port_in_use')
  if (error instanceof Error && error.message === 'cpolar_authtoken_invalid') {
    return new HttpError(400, 'cpolar_authtoken_invalid')
  }
  if (error instanceof Error && error.message.startsWith('cpolar_')) {
    return new HttpError(409, error.message)
  }
  if (error instanceof Error && [
    'frp_server_address_invalid',
    'frp_server_port_invalid',
    'frp_token_invalid',
    'frp_public_origin_invalid',
    'frp_settings_invalid',
  ].includes(error.message)) return new HttpError(400, error.message)
  if (error instanceof Error && error.message.startsWith('frp_')) {
    return new HttpError(409, error.message)
  }
  return new HttpError(500, 'internal_error')
}

/** Fixed first line spoken to the user right after a new session is created (方案 A). */
export const AMADEUS_OPENING_PROMPT = '你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话'

/** 固定开场脚本轮换（架构 3.18）：create 后按会话计数轮换，稳定优先。 */
export const AMADEUS_OPENING_LINES = [
  '你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话',
  '新的一天开始了。你在海边遇见鲸鱼娘，她刚睡醒，和你打个招呼吧，说一句温柔的话',
  '月夜，礁石，海风。你又一次来到海边，鲸鱼娘在等你，打个招呼吧，说一句温柔的话',
]

export function openingLineFor(index: number): string {
  return AMADEUS_OPENING_LINES[index % AMADEUS_OPENING_LINES.length] ?? AMADEUS_OPENING_PROMPT
}

/** Create an Amadeus session, then immediately deliver the opening line (方案 A). */
export async function createAmadeusOpeningSession(
  sessions: AmadeusSessionsAdapter,
  commands: AmadeusSessionCommands,
  mode: string,
  title?: string,
  workspaceId?: string,
  openingIndex = 0,
): Promise<AmadeusSessionSummary> {
  const created = await sessions.create(mode, title, workspaceId)
  await commands.prompt(created.id, openingLineFor(openingIndex))
  return created
}

/**
 * Bridge an SSE stream to the choices adapter: register the stream's write
 * function for the session before pumping and unregister once it ends (either
 * naturally or via the returned close handle).
 */
export function bridgeAmadeusChoicesToStream(
  choices: { registerStream(sessionId: string, push: (frame: object) => void): () => void },
  approval: { registerStream(sessionId: string, push: (frame: object) => void): () => void },
  stream: { open(sessionId: string, write: (data: string) => void, onFinished?: () => void): Promise<() => void> },
): (sessionId: string, write: (data: string) => void, onFinished?: () => void) => Promise<() => void> {
  return async (sessionId, write, onFinished) => {
    let closed = false
    const unregisterChoice = choices.registerStream(sessionId, frame => write(JSON.stringify(frame)))
    const unregisterApproval = approval.registerStream(sessionId, frame => write(JSON.stringify(frame)))
    let close: (() => void) | undefined
    try {
      close = await stream.open(sessionId, write, () => {
        if (closed) return
        closed = true
        unregisterChoice()
        unregisterApproval()
        onFinished?.()
      })
    } catch (error) {
      closed = true
      unregisterChoice()
      unregisterApproval()
      throw error
    }
    return () => {
      if (closed) return
      closed = true
      unregisterChoice()
      unregisterApproval()
      close?.()
    }
  }
}

/** Amadeus business state dir shared by reports, previews and choices. */
function amadeusStateDir(): string {
  return join(homedir(), '.dsh', 'amadeus')
}

/** Setup keys merged into a fixed config (version-1 legacy setup files). */
const SETUP_KEYS = new Set([
  'version', 'publicOrigin', 'listenHost', 'listenPort', 'upstreamOrigin',
  'publicAuthorities', 'allowedCidrs', 'instanceId', 'pairingCaFile', 'tls',
])

type LoadedSetup = {
  readonly kind: 'fixed'
  readonly config: PluginConfig
} | {
  readonly kind: 'managed'
  readonly config: PluginConfig
  readonly setup: ManagedSetup
}

function withoutSetupKeys(config: PluginConfig): PluginConfig {
  const merged = { ...config } as Record<string, unknown>
  for (const key of SETUP_KEYS) if (key !== 'version') delete merged[key]
  return merged as unknown as PluginConfig
}

/** Load the optional setup file: absent → fixed; version 2 → managed LAN mode. */
async function loadSetup(config: PluginConfig): Promise<LoadedSetup> {
  if (config.setupFile === undefined) return { kind: 'fixed', config }
  if (!isAbsolute(config.setupFile)) throw new Error('setupFile must be an absolute file path')
  let source: string
  try {
    source = await readFile(resolve(config.setupFile), 'utf8')
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return { kind: 'fixed', config }
    throw error
  }
  let parsed: unknown
  try { parsed = JSON.parse(source) as unknown }
  catch (error) { throw new Error('amadeus setup file is not valid JSON', { cause: error }) }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('amadeus setup file must be an object')
  }
  const record = parsed as Record<string, unknown>
  if (record.version === 2) {
    return { kind: 'managed', config: withoutSetupKeys(config), setup: parseManagedSetup(record) }
  }
  if (record.version !== 1 || Reflect.ownKeys(record).some(key => typeof key !== 'string' || !SETUP_KEYS.has(key))) {
    throw new Error('amadeus setup file has an unsupported format')
  }
  const { version: _version, ...setup } = record
  return {
    kind: 'fixed',
    config: { ...withoutSetupKeys(config), ...setup } as unknown as PluginConfig,
  }
}

/** Loopback template used for the control-plane config and remote gateways. */
function loopbackTemplate(loaded: LoadedSetup): ResolvedGatewayConfig {
  const base = withoutSetupKeys(loaded.config)
  return parseGatewayConfig({
    ...base,
    ...(loaded.kind === 'managed'
      ? { upstreamOrigin: loaded.setup.upstreamOrigin }
      : loaded.config.upstreamOrigin === undefined ? {} : { upstreamOrigin: loaded.config.upstreamOrigin }),
    // 远程网关也要提供 /amadeus/ca.cer（App bootstrap 拉 CA）——pairingCaFile 来自 managed setup 的 CA 证书
    ...(loaded.kind === 'managed' ? { pairingCaFile: loaded.setup.tls.caCertFile } : {}),
    listenHost: '127.0.0.1',
    listenPort: 0,
    publicAuthorities: ['127.0.0.1'],
    allowedCidrs: ['127.0.0.0/8'],
    tls: { mode: 'disabled' },
  })
}

/** Close every remote provider controller, reporting the first failure. */
async function settleRemoteControllers(
  controllers: Record<RemoteProvider, RemoteProviderController>,
): Promise<void> {
  const results = await Promise.allSettled(
    (Object.keys(controllers) as RemoteProvider[]).map(provider => controllers[provider].close()),
  )
  const failures = results
    .filter(result => result.status === 'rejected')
    .map(result => result.reason as unknown)
  if (failures.length === 1 && failures[0] instanceof Error) throw failures[0]
  if (failures.length > 0) throw new AggregateError(failures, 'amadeus remote provider cleanup failed')
}

/** Mount the independent Amadeus gateway: pairing, sessions, reports, choices. */
export async function apply(ctx: Context, config: PluginConfig): Promise<void> {
  // Make sure the desktop preset exists (fire-and-forget; discovery re-reads).
  void ensureAmadeusPreset().catch(() => {})

  const loaded = await loadSetup(config)
  const resolved = loopbackTemplate(loaded)
  const amadeusAccess = createMobileAccessService(ctx)
  const upstreamLoginUrl = upstreamAuthenticatedUrl(ctx, resolved.upstreamOrigin)

  // Real business adapters over the DSH Cordis services injected by the host.
  const stateDir = amadeusStateDir()
  const sessionRegistry = new AmadeusSessionRegistry(amadeusSessionRegistryFile(stateDir))
  const sessionsContext = {
    ...(ctx as object),
    modeId: AMADEUS_MODE_ID,
    // 默认 Amadeus 工作区目录：会话未选工作区时创建于此（dsh 3080 可见）
    defaultWorkspaceDir: join(amadeusStateDir(), 'workspace'),
  } as unknown as AmadeusSessionsContext
  // DSH services resolve lazily through Cordis `ctx.get`; a spread snapshot at
  // apply-time may hold a stale/absent reference. Rebind the service accessors
  // to live lookups so every call sees the current instance.
  const live = ctx as unknown as {
    get?: (name: string) => unknown
  }
  const lookup = (name: string): unknown => (typeof live.get === 'function' ? live.get(name) : undefined)
  Object.defineProperty(sessionsContext, 'sessionController', {
    get: () => lookup('sessionController'),
    enumerable: false,
    configurable: true,
  })
  Object.defineProperty(sessionsContext, 'sessionQuery', {
    get: () => lookup('sessionQuery'),
    enumerable: false,
    configurable: true,
  })
  Object.defineProperty(sessionsContext, 'workspaceRegistry', {
    get: () => lookup('workspaceRegistry'),
    enumerable: false,
    configurable: true,
  })
  const sessionsAdapter = new AmadeusSessionsAdapter(sessionsContext, sessionRegistry)
  const sessionCommands = new AmadeusSessionCommands(sessionsContext, sessionRegistry)
  const reportsAdapter = new AmadeusReportsAdapter(ctx, stateDir)
  const previewStore = new AmadeusPreviewStore(stateDir)
  const choicesAdapter = new AmadeusChoicesAdapter(ctx as unknown as AmadeusChoicesContext, stateDir)
  const approvalAdapter = new AmadeusApprovalAdapter(ctx as unknown as AmadeusApprovalContext)
  const streamHub = new AmadeusStreamHub(sessionsContext)

  registerAmadeusTools(ctx, reportsAdapter, previewStore)
  choicesAdapter.install()
  approvalAdapter.install()

  const business: AmadeusGatewayOptions = {
    sessions: {
      list: async mode => sessionsAdapter.list(mode),
      // 新建会话不发开场消息：App 默认演出（空对话等待，用户说话才回应）
      create: (mode, title, workspaceId) => sessionsAdapter.create(mode, title, workspaceId),
      get: id => sessionsAdapter.get(id),
    },
    reports: reportsAdapter,
    choices: choicesAdapter,
    commands: sessionCommands,
    workspaces: {
      list: async () => {
        const records = await sessionsContext.workspaceRegistry.list()
        return records.map(record => ({
          id: record.header.id,
          path: record.header.path ?? '',
          title: record.header.title ?? '',
        }))
      },
      browse: async (path) => {
        // App 内目录浏览：dsh directoryPicker 的 browse 后端（directory-picker-mobile-host）
        const picker = lookup('directoryPicker') as
          | { capability(): { kind: 'browse'; list(path?: string, signal?: AbortSignal): Promise<{
              path: string; home: string
              crumbs: Array<{ name: string; path: string }>
              entries: Array<{ name: string; path: string; hidden: boolean }>
            }> } }
          | undefined
        if (picker === undefined) throw new AmadeusRequestError(503, 'amadeus_directory_picker_unavailable')
        const capability = picker.capability()
        if (capability.kind !== 'browse') throw new AmadeusRequestError(503, 'amadeus_directory_picker_unavailable')
        return capability.list(path)
      },
      register: async (path) => {
        const existing = await sessionsContext.workspaceRegistry.resolveByPath(path)
        if (existing !== undefined) {
          return { id: existing.id, path: existing.path, title: existing.title ?? '' }
        }
        const created = await sessionsContext.workspaceRegistry.create(path)
        return { id: created.id, path: created.path, title: created.title ?? '' }
      },
    },
    previews: previewStore,
    approval: approvalAdapter,
    stream: { open: bridgeAmadeusChoicesToStream(choicesAdapter, approvalAdapter, streamHub) },
  }

  const holder: { gateway?: MobileAccessGateway | undefined } = {}

  // LAN 与远程隧道共享配对窗口槽：pairing/open 生成的 token 必须能被远程 native-pair
  // 验证（否则远程 401）。只共享 pairingWindow，设备/会话表保持各自独立。
  const sharedPairingWindow: { window: PairingWindow | undefined } = { window: undefined }

  // Managed mode re-materializes the LAN config when the selected interface's
  // address changes; fixed mode uses the resolved config once.
  const startGateway = async (candidate: ResolvedGatewayConfig): Promise<MobileAccessRuntime> => {
    const gateway = new MobileAccessGateway(
      candidate,
      new JsonDeviceStore(candidate.stateFile, candidate.maxDevices),
      amadeusAccess,
      upstreamLoginUrl,
      {
        pairingTtlMs: resolved.pairingTtlMs,
        deviceTtlMs: resolved.deviceTtlMs,
        sessionTtlMs: resolved.sessionTtlMs,
        maxDevices: resolved.maxDevices,
        maxSessions: resolved.maxSessions,
        rateLimitWindowMs: resolved.rateLimitWindowMs,
        maxPairingAttempts: resolved.maxPairingAttempts,
        maxRateLimitKeys: resolved.maxRateLimitKeys,
        sharedPairingWindow,
      },
    )
    await gateway.start()
    holder.gateway = gateway
    const disposeExtension = amadeusAccess.registerExtension(createAmadeusExtension(business))
    return {
      close: async () => {
        disposeExtension()
        if (holder.gateway === gateway) holder.gateway = undefined
        await gateway.close()
      },
    }
  }

  const startRuntime = async (): Promise<MobileAccessRuntime> => {
    if (loaded.kind === 'managed') {
      const following = new FollowingMobileAccessRuntime(async () => {
        const network = await materializeManagedSetup(loaded.setup)
        const merged = { ...loaded.config, ...network } as unknown as PluginConfig
        return {
          key: `${loaded.setup.networkInterface}\0${String(network.listenHost ?? '')}`,
          start: async () => startGateway(parseGatewayConfig(merged)),
        }
      }, (error) => {
        process.emitWarning(`Amadeus could not follow the current LAN address: ${error instanceof Error ? error.message : String(error)}`, {
          code: 'AMADEUS_NETWORK_REFRESH',
        })
      })
      await following.initialize(2_000)
      return following
    }
    return startGateway(parseGatewayConfig(config))
  }

  const lanController = new MobileAccessGatewayController(
    new JsonMobileAccessControlStore(parseControlFile(config.controlFile), config.initiallyEnabled),
    startRuntime,
  )

  // ── Full remote providers (tailscale / cpolar / frp) on the independent gateway ──
  const instanceId = amadeusInstanceId(resolved.stateFile)
  const remoteDeviceFile = amadeusRemoteDeviceFile()
  const remoteProviderStore = new JsonRemoteProviderStore(
    amadeusRemoteProviderFile(),
    amadeusDefaultRemoteProvider(process.env),
  )
  const initialRemoteProvider = (await remoteProviderStore.load()).provider
  const cpolarComponent = new CpolarComponentManager({ stateDirectory: amadeusCpolarStateDirectory() })
  await cpolarComponent.initialize()
  const frpComponent = new FrpComponentManager({ stateDirectory: amadeusRemoteDirectory() })
  await frpComponent.initialize()
  const frpConfig = new FrpConfigStore(amadeusFrpConfigDirectory())
  await frpConfig.initialize()

  // Remote gateways share the LAN's business extension but own a digest-isolated
  // device store, so tunnel sessions never collide with LAN pairing.
  const createRemoteGateway = async (publicOrigin: string, listenPort = 0): Promise<MobileAccessGateway> => {
    // 远程 instanceId 必须 = pairingCaFile 的 CA fingerprint（gateway.start 校验）
    let remoteInstanceId = instanceId
    if (resolved.pairingCaFile !== undefined) {
      try {
        const caPem = await readFile(resolved.pairingCaFile, 'utf8')
        const ca = new X509Certificate(caPem)
        remoteInstanceId = ca.fingerprint256.replaceAll(':', '').toLowerCase()
      } catch {
        // 读不到 CA 时退回哈希 instanceId（ca.cer 不可用，配对会失败但网关可起）
      }
    }
    const remoteResolved = amadeusRemoteGatewayConfig(
      resolved,
      publicOrigin,
      remoteDeviceFile,
      remoteInstanceId,
      listenPort,
    )
    const candidate = new MobileAccessGateway(
      remoteResolved,
      new JsonDeviceStore(remoteResolved.stateFile, remoteResolved.maxDevices),
      amadeusAccess,
      upstreamLoginUrl,
      { sharedPairingWindow },
    )
    await candidate.start()
    return candidate
  }

  const controlFiles = amadeusRemoteControlFiles()
  const remoteControllers: Record<RemoteProvider, RemoteProviderController> = {
    tailscale: new FunnelController({
      store: new JsonMobileAccessControlStore(controlFiles.tailscale, false),
      executable: funnelExecutable(import.meta.url),
      stateDirectory: amadeusTailscaleStateDirectory(),
      hostname: `amw-${instanceId.slice(0, 12)}`,
      createGateway: createRemoteGateway,
    }),
    cpolar: new CpolarController({
      store: new JsonMobileAccessControlStore(controlFiles.cpolar, false),
      executable: cpolarComponent.executable,
      configFile: cpolarComponent.configFile,
      region: 'cn',
      createGateway: createRemoteGateway,
    }),
    frp: new FrpController({
      store: new JsonMobileAccessControlStore(controlFiles.frp, false),
      executable: frpComponent.executable,
      config: frpConfig,
      instanceId,
      createGateway: createRemoteGateway,
    }),
  }
  const remoteCoordinator = new RemoteProviderCoordinator(initialRemoteProvider, remoteControllers, remoteProviderStore)
  const remoteController = () => remoteCoordinator.controller()
  const remotePayload = (): Record<string, unknown> => ({
    provider: remoteCoordinator.selected,
    running: remoteController().status().enabled,
    state: remoteController().status().state,
    ...(remoteController().status().origin === undefined ? {} : { origin: remoteController().status().origin }),
    ...(remoteController().status().loginUrl === undefined ? {} : { loginUrl: remoteController().status().loginUrl }),
    ...(remoteController().status().setupUrl === undefined ? {} : { setupUrl: remoteController().status().setupUrl }),
    ...(remoteController().status().errorCode === undefined ? {} : { errorCode: remoteController().status().errorCode }),
    ...(remoteController().gateway() === undefined ? {} : { extensions: remoteController().gateway()!.extensionStatus() }),
    providers: {
      tailscale: { bundled: true, running: remoteControllers.tailscale.status().enabled, state: remoteControllers.tailscale.status().state },
      cpolar: {
        bundled: false,
        running: remoteControllers.cpolar.status().enabled,
        state: remoteControllers.cpolar.status().state,
        component: cpolarComponent.status(),
      },
      frp: {
        bundled: false,
        running: remoteControllers.frp.status().enabled,
        state: remoteControllers.frp.status().state,
        component: frpComponent.status(),
        configuration: frpConfig.status(),
      },
    },
  })

  const controlRoutes = new AmadeusControlRoutes({
    isRunning: () => lanController.isRunning(),
    gateway: () => {
      const gateway = holder.gateway
      return gateway === undefined ? undefined : {
        origin: gateway.address().origin,
        devices: () => gateway.devices(),
        pairingStatus: () => gateway.access.pairingStatus(),
      }
    },
    remoteProvider: () => remoteCoordinator.selected,
    remoteStatus: () => remoteController().status(),
  })

  const adminRoute: WebRoute = {
    kind: 'prefix',
    path: LOCAL_ADMIN_PREFIX,
    handler: async (request, response) => {
      try {
        const target = parseRequestTarget(request.url)
        assertLocalAdminTrust(request, request.method === 'POST')
        if (target.search !== '') throw new HttpError(400, 'bad_request')
        const control = target.decodedPathname === LOCAL_ADMIN_PREFIX
          || target.decodedPathname === `${LOCAL_ADMIN_PREFIX}/control`
        if (request.method === 'GET' && control) {
          const result = controlRoutes.controlGet()
          sendJson(response, result.status, JSON.parse(result.body) as unknown, false)
          return
        }
        if (request.method === 'POST' && control) {
          const body = await readJsonObject(request, 4096)
          if (typeof body.running !== 'boolean') throw new HttpError(400, 'bad_request')
          await lanController.setRunning(body.running)
          sendJson(response, 200, { running: lanController.isRunning() }, false)
          return
        }
        // Remote control surface: provider switch + per-provider component/config.
        const remotePrefix = `${LOCAL_ADMIN_PREFIX}/remote`
        const remoteControl = target.decodedPathname === `${remotePrefix}/control`
        if (request.method === 'GET' && target.decodedPathname === `${remotePrefix}/control`) {
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/provider`) {
          const body = await readJsonObject(request, 4096)
          if (body.provider !== 'tailscale' && body.provider !== 'cpolar' && body.provider !== 'frp') {
            throw new HttpError(400, 'bad_request')
          }
          await remoteCoordinator.select(body.provider)
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/cpolar/component/install`) {
          const body = await readJsonObject(request, 4096)
          if (body.confirm !== true) throw new HttpError(400, 'bad_request')
          await remoteCoordinator.mutate(async () => cpolarComponent.install())
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/cpolar/configure`) {
          const body = await readJsonObject(request, 4096)
          await remoteCoordinator.mutate(async () => cpolarComponent.configure(body.authtoken))
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/frp/component/install`) {
          const body = await readJsonObject(request, 4096)
          if (body.confirm !== true) throw new HttpError(400, 'bad_request')
          await remoteCoordinator.mutate(async () => frpComponent.install())
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/frp/configure`) {
          const body = await readJsonObject(request, 4096)
          await remoteCoordinator.mutate(async () => {
            await frpConfig.configure(body)
            if (remoteControllers.frp.status().enabled) await remoteControllers.frp.reconnect()
          })
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/frp/component/purge`) {
          const body = await readJsonObject(request, 4096)
          if (body.confirm !== true) throw new HttpError(400, 'bad_request')
          await remoteCoordinator.mutate(async () => {
            await remoteControllers.frp.setEnabled(false)
            await Promise.all([frpComponent.purge(), frpConfig.purge()])
          })
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && remoteControl) {
          const body = await readJsonObject(request, 4096)
          const running = body.running
          if (typeof running !== 'boolean') throw new HttpError(400, 'bad_request')
          await remoteCoordinator.mutate(async controller => controller.setEnabled(running))
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/reconnect`) {
          await readJsonObject(request, 4096)
          await remoteCoordinator.mutate(async controller => controller.reconnect())
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (request.method === 'POST' && target.decodedPathname === `${remotePrefix}/reset`) {
          const body = await readJsonObject(request, 4096)
          if (body.confirm !== true) throw new HttpError(400, 'bad_request')
          await remoteCoordinator.mutate(async controller => {
            await controller.reset()
            const { rm } = await import('node:fs/promises')
            await rm(remoteDeviceFile, { force: true })
          })
          sendJson(response, 200, remotePayload(), false)
          return
        }
        if (target.decodedPathname.startsWith(`${remotePrefix}/`)) {
          const active = remoteController().gateway()
          if (active === undefined) throw new HttpError(409, 'gateway_stopped')
          await active.localAdminRoute(remotePrefix).handler(request, response)
          return
        }
        // Everything else delegates to the gateway's own local admin surface.
        const gateway = holder.gateway
        if (gateway === undefined) throw new HttpError(409, 'gateway_stopped')
        await gateway.localAdminRoute().handler(request, response)
      } catch (error) {
        const mapped = mapAdminError(error)
        if (response.headersSent) response.destroy()
        else sendFailure(response, mapped.status, mapped.code, false)
      }
    },
  }

  await ctx.effect(async () => {
    const unregister = ctx.webServer.register(adminRoute)
    await lanController.initialize()
    await Promise.all((Object.keys(remoteControllers) as RemoteProvider[])
      .filter(provider => provider !== remoteCoordinator.selected)
      .map(async provider => {
        const store = new JsonMobileAccessControlStore(controlFiles[provider], false)
        await store.save({ version: 1, enabled: false })
      }))
    for (const provider of ['tailscale', 'cpolar', 'frp'] as const) {
      await remoteControllers[provider].initialize()
    }
    return async () => {
      await settleRemoteControllers(remoteControllers)
      await lanController.close()
      unregister()
    }
  }, 'dsh-amadeus: independent gateway + remote providers + amadeus preset')
}
