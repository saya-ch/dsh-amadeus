import { describe, expect, it } from 'vitest'
import { AmadeusControlRoutes } from '../src/amadeus-control.js'
import type { DeviceSummary } from '../src/access.js'

function fakeGateway(): {
  origin: string
  devices(): readonly DeviceSummary[]
  pairingStatus(): { open: boolean; expiresAt?: number }
} {
  return {
    origin: 'https://amadeus.local:3444',
    devices: () => [
      { id: 'd1', label: 'Phone', createdAt: 1, expiresAt: 2, lastSeenAt: 3 },
    ],
    pairingStatus: () => ({ open: false }),
  }
}

function remoteOptions() {
  return {
    remoteProvider: () => 'frp' as const,
    remoteStatus: () => ({ enabled: false, state: 'off' }),
  }
}

describe('control routes', () => {
  it('reports running from lan controller', async () => {
    const routes = new AmadeusControlRoutes({
      isRunning: () => true,
      gateway: () => fakeGateway(),
      ...remoteOptions(),
    })
    const res = await routes.controlGet()
    const body = JSON.parse(res.body as string) as Record<string, unknown>
    expect(body.running).toBe(true)
    expect((body.devices as Array<{ label: string }>)[0]!.label).toBe('Phone')
  })

  it('reports pairing open from the gateway pairing window', async () => {
    const gateway = fakeGateway()
    gateway.pairingStatus = () => ({ open: true, expiresAt: 1_700_000_000_000 })
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => gateway, ...remoteOptions() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.pairingOpen).toBe(true)
  })

  it('exposes the gateway origin for the panel link', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => fakeGateway(), ...remoteOptions() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.origin).toBe('https://amadeus.local:3444')
  })

  it('reports empty devices and closed pairing while the gateway is stopped', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => false, gateway: () => undefined, ...remoteOptions() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.running).toBe(false)
    expect(body.devices).toEqual([])
    expect(body.pairingOpen).toBe(false)
    expect(body.origin).toBeUndefined()
  })

  it('maps devices to the client shape with lastSeenAt', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => fakeGateway(), ...remoteOptions() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.devices).toEqual([{ id: 'd1', label: 'Phone', lastSeenAt: 3 }])
  })

  it('includes remote status when present', () => {
    const routes = new AmadeusControlRoutes({
      isRunning: () => true,
      gateway: () => ({ origin: 'https://127.0.0.1:3444', devices: () => [], pairingStatus: () => ({ open: false }) }),
      remoteProvider: () => 'frp',
      remoteStatus: () => ({ enabled: true, state: 'running', origin: 'https://amw.example.com' }),
    })
    const parsed = JSON.parse(routes.controlGet().body) as { remote?: { provider: string; enabled: boolean; state: string; origin?: string } }
    expect(parsed.remote).toEqual({ provider: 'frp', enabled: true, state: 'running', origin: 'https://amw.example.com' })
  })

  it('filters pid from remote status', () => {
    const routes = new AmadeusControlRoutes({
      isRunning: () => true,
      gateway: () => ({ origin: 'https://127.0.0.1:3444', devices: () => [], pairingStatus: () => ({ open: false }) }),
      remoteProvider: () => 'frp',
      remoteStatus: () => ({ enabled: true, state: 'running', origin: 'https://amw.example.com', pid: 12345 }),
    })
    const body = routes.controlGet().body
    expect(body).not.toContain('"pid"')
    const parsed = JSON.parse(body) as { remote?: Record<string, unknown> }
    expect(parsed.remote).toEqual({ provider: 'frp', enabled: true, state: 'running', origin: 'https://amw.example.com' })
    expect(parsed.remote).not.toHaveProperty('pid')
  })
})

