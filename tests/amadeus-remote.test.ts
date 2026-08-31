import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createServer } from 'node:net'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  AmadeusRemoteCoordinator, FrpController, JsonRemoteStore, NoopRemoteController, parseFrpConfig,
  type AmadeusRemoteController, type SpawnHandle,
} from '../src/amadeus-remote.js'

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

interface SpawnRecord {
  command: string
  args: string[]
  killed: boolean
}

function recordingSpawn(record: SpawnRecord, pid = 4242) {
  return (command: string, args: string[], _options: object): SpawnHandle => {
    record.command = command
    record.args = args
    return {
      pid,
      on: () => undefined,
      kill: () => { record.killed = true; return true },
    }
  }
}

function controllableSpawn() {
  let exitHandler: ((code?: number | null, signal?: string | null) => void) | undefined
  const handle: SpawnHandle & { triggerExit(code?: number | null): void } = {
    pid: 1234,
    on: (event: string, cb) => {
      if (event === 'exit') exitHandler = cb
      return handle
    },
    kill: () => true,
    triggerExit: (code) => { exitHandler?.(code) },
  }
  return { handle, spawn: (): SpawnHandle => handle }
}

interface ControllableHandle {
  handle: SpawnHandle & { triggerExit(code?: number | null): void; triggerError(): void }
  killed: boolean
}

function sequenceSpawn() {
  const instances: ControllableHandle[] = []
  const spawn = (_command: string, _args: string[], _options: object): SpawnHandle => {
    let exitHandler: ((code?: number | null, signal?: string | null) => void) | undefined
    let errorHandler: (() => void) | undefined
    const instance: ControllableHandle = {
      handle: {
        pid: 1000 + instances.length,
        on: (event: string, cb) => {
          if (event === 'exit') exitHandler = cb
          if (event === 'error') errorHandler = cb
          return instance.handle
        },
        kill: () => { instance.killed = true; return true },
        triggerExit: (code) => { exitHandler?.(code) },
        triggerError: () => { errorHandler?.() },
      },
      killed: false,
    }
    instances.push(instance)
    return instance.handle
  }
  return { spawn, instances }
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

describe('frp controller', () => {
  it('reports unconfigured when no config file exists', async () => {
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.initialize()
    expect(frp.status()).toEqual({ enabled: false, state: 'unconfigured' })
  })

  it('is off before enable when configured', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.initialize()
    expect(frp.status()).toEqual({ enabled: false, state: 'off' })
  })

  it('starts and stops a child process', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record) })
    await frp.initialize()
    await frp.setEnabled(true)
    expect(frp.status()).toEqual({ enabled: true, state: 'running', pid: 4242 })
    expect(record.command).toBe('frpc')
    expect(record.args).toEqual(['-c', join(dir, 'frpc-run.toml')])
    const toml = await readFile(join(dir, 'frpc-run.toml'), 'utf8')
    expect(toml).toContain('serverAddr = "example.com"')
    expect(toml).toContain('serverPort = 7000')
    expect(toml).toContain('remotePort = 7000')
    await frp.setEnabled(false)
    expect(frp.status()).toEqual({ enabled: false, state: 'off' })
    expect(record.killed).toBe(true)
  })

  it('reflects the enabled flag without a fabricated origin', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record) })
    await frp.initialize()
    await frp.setEnabled(true)
    expect(frp.status().enabled).toBe(true)
    expect(frp.status().origin).toBeUndefined()
  })

  it('disable and close leave it off', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record) })
    await frp.initialize()
    await frp.setEnabled(true)
    await frp.setEnabled(false)
    expect(frp.status().enabled).toBe(false)
    await frp.close()
    expect(frp.status()).toEqual({ enabled: false, state: 'off' })
  })

  it('close kills the child and removes the runtime config', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record) })
    await frp.initialize()
    await frp.setEnabled(true)
    await frp.close()
    expect(record.killed).toBe(true)
    expect(frp.status()).toEqual({ enabled: false, state: 'off' })
    await expect(readFile(join(dir, 'frpc-run.toml'), 'utf8')).rejects.toMatchObject({ code: 'ENOENT' })
  })

  it('marks the process as failed when the child exits', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const { handle, spawn } = controllableSpawn()
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn })
    await frp.initialize()
    await frp.setEnabled(true)
    expect(frp.status().state).toBe('running')
    handle.triggerExit(1)
    expect(frp.status()).toEqual({ enabled: false, state: 'failed', errorCode: 'frpc_exit_1' })
  })

  it('reports endpoint_unreachable when the public origin is not reachable', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({
      serverAddress: 'example.com', serverPort: 7000, publicOrigin: 'http://127.0.0.1:1',
    }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record), reachabilityTimeoutMs: 300 })
    await frp.initialize()
    await frp.setEnabled(true)
    expect(frp.status().state).toBe('running')
    await vi.waitFor(() => expect(frp.status().errorCode).toBe('endpoint_unreachable'))
  })

  it('fills origin only when the public endpoint is reachable', async () => {
    const server = createServer()
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()))
    const port = (server.address() as { port: number }).port
    const origin = `http://127.0.0.1:${port}`
    await writeFile(join(dir, 'frp.json'), JSON.stringify({
      serverAddress: 'example.com', serverPort: 7000, publicOrigin: origin,
    }))
    const record: SpawnRecord = { command: '', args: [], killed: false }
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn: recordingSpawn(record) })
    await frp.initialize()
    await frp.setEnabled(true)
    await vi.waitFor(() => expect(frp.status().origin).toBe(origin))
    expect(frp.status().errorCode).toBeUndefined()
    await new Promise<void>((resolve) => server.close(() => resolve()))
  })

  it('parses minimal and invalid frp configs', () => {
    expect(parseFrpConfig({ serverAddress: 'frp.example.com', serverPort: 7000 })).toEqual({
      serverAddress: 'frp.example.com', serverPort: 7000,
    })
    expect(() => parseFrpConfig({ serverPort: 7000 })).toThrow('serverAddress')
    expect(() => parseFrpConfig({ serverAddress: 'x', serverPort: 0 })).toThrow('serverPort')
    expect(() => parseFrpConfig({ serverAddress: 'x', serverPort: 65536 })).toThrow('serverPort')
    expect(() => parseFrpConfig([])).toThrow('frp config must be an object')
  })

  it('ignores a stale exit from a previous child after respawn', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const { spawn, instances } = sequenceSpawn()
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn })
    await frp.initialize()
    await frp.setEnabled(true)
    const first = instances[0]!
    await frp.setEnabled(false)
    expect(first.killed).toBe(true)
    await frp.setEnabled(true)
    const second = instances[1]!
    expect(frp.status()).toEqual({ enabled: true, state: 'running', pid: second.handle.pid })
    first.handle.triggerExit(1)
    expect(frp.status()).toEqual({ enabled: true, state: 'running', pid: second.handle.pid })
    expect(frp.status().errorCode).toBeUndefined()
  })

  it('ignores a stale error from a previous child after respawn', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com', serverPort: 7000 }))
    const { spawn, instances } = sequenceSpawn()
    const frp = new FrpController(dir, join(dir, 'frp.json'), { spawn })
    await frp.initialize()
    await frp.setEnabled(true)
    const first = instances[0]!
    await frp.setEnabled(false)
    await frp.setEnabled(true)
    const second = instances[1]!
    first.handle.triggerError()
    expect(frp.status()).toEqual({ enabled: true, state: 'running', pid: second.handle.pid })
    expect(frp.status().errorCode).toBeUndefined()
  })

  it('reports invalid_frp_config when the config file is corrupt', async () => {
    await writeFile(join(dir, 'frp.json'), 'not json {')
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.initialize()
    expect(frp.status()).toEqual({ enabled: false, state: 'unconfigured', errorCode: 'invalid_frp_config' })
  })

  it('reports invalid_frp_config when the config is missing required fields', async () => {
    await writeFile(join(dir, 'frp.json'), JSON.stringify({ serverAddress: 'example.com' }))
    const frp = new FrpController(dir, join(dir, 'frp.json'))
    await frp.initialize()
    expect(frp.status()).toEqual({ enabled: false, state: 'unconfigured', errorCode: 'invalid_frp_config' })
  })
})

describe('noop remote controller', () => {
  it('is off and throws on enable', async () => {
    const noop = new NoopRemoteController()
    await noop.initialize()
    expect(noop.status()).toEqual({ enabled: false, state: 'off' })
    await expect(noop.setEnabled(true)).rejects.toThrow('unsupported provider')
    await noop.setEnabled(false).catch(() => {})
    await noop.close()
  })

  it('setEnabled throws for both on and off', async () => {
    const noop = new NoopRemoteController()
    await expect(noop.setEnabled(true)).rejects.toThrow('unsupported provider: remote disabled')
    await expect(noop.setEnabled(false)).rejects.toThrow('unsupported provider: remote disabled')
  })
})
