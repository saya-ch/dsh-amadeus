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

describe('control routes', () => {
  it('reports running from lan controller', async () => {
    const routes = new AmadeusControlRoutes({
      isRunning: () => true,
      gateway: () => fakeGateway(),
    })
    const res = await routes.controlGet()
    const body = JSON.parse(res.body as string) as Record<string, unknown>
    expect(body.running).toBe(true)
    expect((body.devices as Array<{ label: string }>)[0]!.label).toBe('Phone')
  })

  it('reports pairing open from the gateway pairing window', async () => {
    const gateway = fakeGateway()
    gateway.pairingStatus = () => ({ open: true, expiresAt: 1_700_000_000_000 })
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => gateway })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.pairingOpen).toBe(true)
  })

  it('exposes the gateway origin for the panel link', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => fakeGateway() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.origin).toBe('https://amadeus.local:3444')
  })

  it('reports empty devices and closed pairing while the gateway is stopped', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => false, gateway: () => undefined })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.running).toBe(false)
    expect(body.devices).toEqual([])
    expect(body.pairingOpen).toBe(false)
    expect(body.origin).toBeUndefined()
  })

  it('maps devices to the client shape with lastSeenAt', async () => {
    const routes = new AmadeusControlRoutes({ isRunning: () => true, gateway: () => fakeGateway() })
    const body = JSON.parse((await routes.controlGet()).body as string) as Record<string, unknown>
    expect(body.devices).toEqual([{ id: 'd1', label: 'Phone', lastSeenAt: 3 }])
  })
})