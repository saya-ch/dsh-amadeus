import { homedir } from 'node:os'
import { join } from 'node:path'
import type { Context } from '@deepseek-ai/cordis'
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import { MobileAccessGateway } from './gateway.js'
import { createMobileAccessService } from './extensions.js'
import { createAmadeusExtension, type AmadeusGatewayOptions, type AmadeusSessionSummary } from './amadeus-extension.js'
import { AmadeusSessionCommands, AmadeusSessionsAdapter, type AmadeusSessionsContext } from './amadeus-sessions.js'
import { AmadeusPreviewStore, AmadeusReportsAdapter } from './amadeus-reports.js'
import { AmadeusChoicesAdapter, type AmadeusChoicesContext } from './amadeus-choices.js'
import { AmadeusStreamHub } from './amadeus-stream.js'
import { registerAmadeusTools } from './amadeus-tools.js'
import { AmadeusControlRoutes } from './amadeus-control.js'
import { JsonDeviceStore } from './storage.js'
import { JsonMobileAccessControlStore, MobileAccessGatewayController, type MobileAccessRuntime } from './control.js'
import { parseControlFile, parseGatewayConfig, type PluginConfig } from './config.js'
import { assertLocalAdminTrust, HttpError, LOCAL_ADMIN_PREFIX, parseRequestTarget, readJsonObject, sendFailure, sendJson } from './http-security.js'
import { Config as GatewayConfigSchema } from './config.js'

/** Stable Cordis plugin name. Fully independent from dsh-mobile. */
export const name = 'dsh-amadeus'

/** Own webserver route + loopback DSH connection; no shared services with dsh-mobile. */
export const inject = ['webServer', 'connection']

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
  return new HttpError(500, 'internal_error')
}

/** Fixed first line spoken to the user right after a new session is created (方案 A). */
export const AMADEUS_OPENING_PROMPT = '你刚在月夜礁石边遇见用户，打个招呼吧，说一句温柔的话'

/** Create an Amadeus session, then immediately deliver the opening line (方案 A). */
export async function createAmadeusOpeningSession(
  sessions: AmadeusSessionsAdapter,
  commands: AmadeusSessionCommands,
  mode: string,
  title?: string,
  workspaceId?: string,
): Promise<AmadeusSessionSummary> {
  const created = await sessions.create(mode, title, workspaceId)
  await commands.prompt(created.id, AMADEUS_OPENING_PROMPT)
  return created
}

/**
 * Bridge an SSE stream to the choices adapter: register the stream's write
 * function for the session before pumping and unregister once it ends (either
 * naturally or via the returned close handle).
 */
export function bridgeAmadeusChoicesToStream(
  choices: { registerStream(sessionId: string, push: (frame: object) => void): () => void },
  stream: { open(sessionId: string, write: (data: string) => void, onFinished?: () => void): Promise<() => void> },
): (sessionId: string, write: (data: string) => void, onFinished?: () => void) => Promise<() => void> {
  return async (sessionId, write, onFinished) => {
    let closed = false
    const unregister = choices.registerStream(sessionId, frame => write(JSON.stringify(frame)))
    let close: (() => void) | undefined
    try {
      close = await stream.open(sessionId, write, () => {
        if (closed) return
        closed = true
        unregister()
        onFinished?.()
      })
    } catch (error) {
      closed = true
      unregister()
      throw error
    }
    return () => {
      if (closed) return
      closed = true
      unregister()
      close?.()
    }
  }
}

/** Amadeus business state dir shared by reports, previews and choices. */
function amadeusStateDir(): string {
  return join(homedir(), '.dsh', 'amadeus')
}

/** Mount the independent Amadeus gateway: pairing, sessions, reports, choices. */
export async function apply(ctx: Context, config: PluginConfig): Promise<void> {
  // Make sure the desktop preset exists (fire-and-forget; discovery re-reads).
  void ensureAmadeusPreset().catch(() => {})

  const resolved = parseGatewayConfig(config)
  const amadeusAccess = createMobileAccessService(ctx)
  const upstreamLoginUrl = upstreamAuthenticatedUrl(ctx, resolved.upstreamOrigin)

  // Real business adapters over the DSH Cordis services injected by the host.
  const stateDir = amadeusStateDir()
  const sessionsContext = ctx as unknown as AmadeusSessionsContext
  const sessionsAdapter = new AmadeusSessionsAdapter(sessionsContext)
  const sessionCommands = new AmadeusSessionCommands(sessionsContext)
  const reportsAdapter = new AmadeusReportsAdapter(ctx, stateDir)
  const previewStore = new AmadeusPreviewStore(stateDir)
  const choicesAdapter = new AmadeusChoicesAdapter(ctx as unknown as AmadeusChoicesContext, stateDir)
  const streamHub = new AmadeusStreamHub(sessionsContext)

  registerAmadeusTools(ctx, reportsAdapter, previewStore)
  choicesAdapter.install()

  const business: AmadeusGatewayOptions = {
    sessions: {
      list: mode => sessionsAdapter.list(mode),
      create: (mode, title, workspaceId) => createAmadeusOpeningSession(sessionsAdapter, sessionCommands, mode, title, workspaceId),
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
    },
    previews: previewStore,
    stream: { open: bridgeAmadeusChoicesToStream(choicesAdapter, streamHub) },
  }

  const holder: { gateway?: MobileAccessGateway | undefined } = {}

  const startRuntime = async (): Promise<MobileAccessRuntime> => {
    const gateway = new MobileAccessGateway(
      resolved,
      new JsonDeviceStore(resolved.stateFile, resolved.maxDevices),
      amadeusAccess,
      upstreamLoginUrl,
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

  const lanController = new MobileAccessGatewayController(
    new JsonMobileAccessControlStore(parseControlFile(config.controlFile), config.initiallyEnabled),
    startRuntime,
  )

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
    return async () => {
      await lanController.close()
      unregister()
    }
  }, 'dsh-amadeus: independent gateway + amadeus preset')
}
