import type { Context } from '@deepseek-ai/cordis'
import type { WebRoute } from '@deepseek-ai/dsh-host-webserver'
import { MobileAccessGateway } from './gateway.js'
import { createMobileAccessService } from './extensions.js'
import { createAmadeusExtension, type AmadeusGatewayOptions } from './amadeus-extension.js'
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

/** Mount the independent Amadeus gateway: pairing, sessions, reports, choices. */
export async function apply(ctx: Context, config: PluginConfig): Promise<void> {
  // Make sure the desktop preset exists (fire-and-forget; discovery re-reads).
  void ensureAmadeusPreset().catch(() => {})

  const resolved = parseGatewayConfig(config)
  const amadeusAccess = createMobileAccessService(ctx)
  const upstreamLoginUrl = upstreamAuthenticatedUrl(ctx, resolved.upstreamOrigin)

  // Business adapters are wired later by the sessions plugin; explicit 503 now.
  const business: AmadeusGatewayOptions = {}

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
          sendJson(response, 200, {
            running: lanController.isRunning(),
            ...(holder.gateway === undefined ? {} : { origin: holder.gateway.address().origin }),
          }, false)
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
