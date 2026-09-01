import { describe, expect, it } from 'vitest'
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
} from '../src/amadeus-remote.js'
import { parseGatewayConfig } from '../src/config.js'

/** Minimal resolved config derived from the real parser with a loopback template. */
function resolvedTemplate(): ReturnType<typeof parseGatewayConfig> {
  return parseGatewayConfig({
    stateFile: 'C:/Users/test/.dsh/amadeus/devices.json',
    controlFile: 'C:/Users/test/.dsh/amadeus/control.json',
    initiallyEnabled: false,
    listenHost: '127.0.0.1',
    listenPort: 3444,
    upstreamOrigin: 'http://127.0.0.1:3080',
    allowedCidrs: ['127.0.0.0/8'],
    tls: { mode: 'disabled' },
  })
}

describe('amadeus remote directories', () => {
  it('derives the remote root under the amadeus state dir', () => {
    expect(amadeusRemoteDirectory()).toMatch(/[\\/]\.dsh[\\/]amadeus[\\/]remote$/)
  })

  it('derives per-provider control files', () => {
    const files = amadeusRemoteControlFiles()
    expect(files.tailscale).toMatch(/[\\/]remote[\\/]tailscale[\\/]control\.json$/)
    expect(files.cpolar).toMatch(/[\\/]remote[\\/]cpolar[\\/]control\.json$/)
    expect(files.frp).toMatch(/[\\/]remote[\\/]frp[\\/]control\.json$/)
  })

  it('derives the shared remote device store and provider selection', () => {
    expect(amadeusRemoteDeviceFile()).toMatch(/[\\/]remote[\\/]devices\.json$/)
    expect(amadeusRemoteProviderFile()).toMatch(/[\\/]remote[\\/]provider\.json$/)
    expect(amadeusFrpConfigDirectory()).toMatch(/[\\/]remote[\\/]frp[\\/]config$/)
    expect(amadeusTailscaleStateDirectory()).toMatch(/[\\/]remote[\\/]tailscale$/)
    expect(amadeusCpolarStateDirectory()).toMatch(/[\\/]remote[\\/]cpolar$/)
  })
})

describe('amadeus default remote provider', () => {
  it('defaults to frp without an override', () => {
    expect(amadeusDefaultRemoteProvider({})).toBe('frp')
  })

  it('accepts every supported provider', () => {
    expect(amadeusDefaultRemoteProvider({ AMADEUS_REMOTE_PROVIDER: 'tailscale' })).toBe('tailscale')
    expect(amadeusDefaultRemoteProvider({ AMADEUS_REMOTE_PROVIDER: 'cpolar' })).toBe('cpolar')
    expect(amadeusDefaultRemoteProvider({ AMADEUS_REMOTE_PROVIDER: 'frp' })).toBe('frp')
  })

  it('rejects unsupported values', () => {
    expect(() => amadeusDefaultRemoteProvider({ AMADEUS_REMOTE_PROVIDER: 'other' }))
      .toThrow('AMADEUS_REMOTE_PROVIDER must be tailscale, cpolar, or frp')
  })

  it('ignores the dsh-mobile environment variable', () => {
    // dsh-mobile's DSH_MOBILE_REMOTE_PROVIDER must not leak into amadeus.
    expect(amadeusDefaultRemoteProvider({ DSH_MOBILE_REMOTE_PROVIDER: 'tailscale' })).toBe('frp')
  })
})

describe('amadeus instance id', () => {
  it('derives a stable sha256 from the state file', () => {
    const id = amadeusInstanceId('C:/Users/test/.dsh/amadeus/devices.json')
    expect(id).toMatch(/^[a-f\d]{64}$/u)
    expect(amadeusInstanceId('C:/Users/test/.dsh/amadeus/devices.json')).toBe(id)
  })
})

describe('amadeus remote gateway config', () => {
  it('builds a loopback gateway behind a public HTTPS origin', () => {
    const template = resolvedTemplate()
    const remote = amadeusRemoteGatewayConfig(
      template,
      'https://amw.example.com',
      'C:/Users/test/.dsh/amadeus/remote/devices.json',
      'a'.repeat(64),
    )
    expect(remote.listenHost).toBe('127.0.0.1')
    expect(remote.listenPort).toBe(0)
    expect(remote.publicTls).toBe(true)
    expect(remote.discovery).toBe(false)
    expect(remote.stateFile).toMatch(/remote[\\/]devices\.json$/)
    expect(remote.instanceId).toBe('a'.repeat(64))
    expect(remote.tls.mode).toBe('disabled')
    expect(remote.authorities).toEqual([{ hostname: 'amw.example.com', port: 443 }])
    expect(remote.allowedCidrs).toEqual([expect.objectContaining({ source: '127.0.0.0/8' })])
  })

  it('preserves the configured ephemeral port', () => {
    const template = resolvedTemplate()
    const remote = amadeusRemoteGatewayConfig(
      template,
      'https://amw.example.com',
      'C:/Users/test/.dsh/amadeus/remote/devices.json',
      'a'.repeat(64),
      4321,
    )
    expect(remote.listenPort).toBe(4321)
  })

  it('rejects non-HTTPS public origins', () => {
    const template = resolvedTemplate()
    expect(() => amadeusRemoteGatewayConfig(
      template,
      'http://amw.example.com',
      'C:/Users/test/.dsh/amadeus/remote/devices.json',
      'a'.repeat(64),
    )).toThrow('remote public origin must be an HTTPS origin')
  })

  it('rejects public origins with a path', () => {
    const template = resolvedTemplate()
    expect(() => amadeusRemoteGatewayConfig(
      template,
      'https://amw.example.com/path',
      'C:/Users/test/.dsh/amadeus/remote/devices.json',
      'a'.repeat(64),
    )).toThrow('remote public origin must be an HTTPS origin')
  })
})
