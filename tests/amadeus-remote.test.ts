import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { AmadeusRemoteCoordinator, FrpController, JsonRemoteStore, type AmadeusRemoteController } from '../src/amadeus-remote.js'

let dir: string
beforeEach(async () => { dir = await mkdtemp(join(tmpdir(), 'amw-')) })
afterEach(async () => { await rm(dir, { recursive: true, force: true }) })

function controller(overrides: Partial<AmadeusRemoteController> = {}): AmadeusRemoteController {
  return {
    initialize: async () => {},
    status: () => ({ enabled: false, state: 'off' }),
    setEnabled: async () => {},
    close: async () => {},
    ...overrides,
  }
}

function allControllers(overrides: Partial<Record<'frp' | 'tailscale' | 'cpolar', Partial<AmadeusRemoteController>>> = {}) {
  return {
    frp: controller(overrides.frp),
    tailscale: controller(overrides.tailscale),
    cpolar: controller(overrides.cpolar),
  }
}

describe('json remote store', () => {
  it('loads the default provider when the file is absent', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    expect(await store.load()).toBe('frp')
  })

  it('persists and reloads the selected provider', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    await store.save('tailscale')
    const persisted = JSON.parse(await readFile(join(dir, 'remote.json'), 'utf8')) as { version: number; provider: string }
    expect(persisted).toEqual({ version: 1, provider: 'tailscale' })
    expect(await store.load()).toBe('tailscale')
  })
})

describe('remote coordinator', () => {
  it('starts on the default provider', () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    const coord = new AmadeusRemoteCoordinator(allControllers(), store, 'frp')
    expect(coord.selected).toBe('frp')
  })

  it('initialize loads the persisted provider', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    await store.save('cpolar')
    const coord = new AmadeusRemoteCoordinator(allControllers(), store, 'frp')
    await coord.initialize()
    expect(coord.selected).toBe('cpolar')
  })

  it('select persists the new provider and swaps', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    const coord = new AmadeusRemoteCoordinator(allControllers(), store, 'frp')
    await coord.select('tailscale')
    expect(coord.selected).toBe('tailscale')
    expect(await store.load()).toBe('tailscale')
  })

  it('select disables the previously enabled provider', async () => {
    const calls: string[] = []
    const coord = new AmadeusRemoteCoordinator(allControllers({
      frp: {
        status: () => ({ enabled: true, state: 'ready', origin: 'https://dsh.example.com' }),
        setEnabled: async (on: boolean) => { calls.push(`frp:${on}`) },
      },
    }), new JsonRemoteStore(join(dir, 'remote.json'), 'frp'), 'frp')
    await coord.select('cpolar')
    expect(calls).toEqual(['frp:false'])
    expect(coord.selected).toBe('cpolar')
  })

  it('status reflects the selected controller', () => {
    const coord = new AmadeusRemoteCoordinator(allControllers({
      frp: { status: () => ({ enabled: true, state: 'ready', origin: 'https://dsh.example.com' }) },
    }), new JsonRemoteStore(join(dir, 'remote.json'), 'frp'), 'frp')
    expect(coord.status().origin).toBe('https://dsh.example.com')
  })

  it('serializes concurrent switches', async () => {
    const store = new JsonRemoteStore(join(dir, 'remote.json'), 'frp')
    const coord = new AmadeusRemoteCoordinator(allControllers(), store, 'frp')
    await Promise.all([coord.select('tailscale'), coord.select('cpolar')])
    expect(coord.selected).toBe('cpolar')
    expect(await store.load()).toBe('cpolar')
  })

  it('close shuts down every controller', async () => {
    const closed: string[] = []
    const coord = new AmadeusRemoteCoordinator(allControllers({
      frp: { close: async () => { closed.push('frp') } },
      tailscale: { close: async () => { closed.push('tailscale') } },
      cpolar: { close: async () => { closed.push('cpolar') } },
    }), new JsonRemoteStore(join(dir, 'remote.json'), 'frp'), 'frp')
    await coord.close()
    expect(closed.sort()).toEqual(['cpolar', 'frp', 'tailscale'])
  })
})

describe('frp controller skeleton', () => {
  it('is off before enable', async () => {
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.initialize()
    expect(frp.status()).toEqual({ enabled: false, state: 'off' })
  })

  it('reflects the enabled flag without a fabricated origin', async () => {
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.setEnabled(true)
    expect(frp.status().enabled).toBe(true)
    expect(frp.status().origin).toBeUndefined()
  })

  it('disable and close leave it off', async () => {
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.setEnabled(true)
    await frp.setEnabled(false)
    expect(frp.status().enabled).toBe(false)
    await frp.close()
    expect(frp.status().state).toBe('off')
  })
})