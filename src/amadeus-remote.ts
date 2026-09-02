/**
 * Amadeus remote transport wiring — independent from dsh-mobile.
 *
 * Reuses the full provider stack (tailscale Funnel sidecar, cpolar, restricted
 * FRP) with Amadeus-owned state, instance identity and gateway config. The LAN
 * gateway stays on 3444 with amw_* cookies; each remote provider spawns its
 * own loopback HTTPS gateway behind the public tunnel, exactly like dsh-mobile
 * but under `~/.dsh/amadeus/`.
 */
import { createHash } from 'node:crypto'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { ResolvedGatewayConfig } from './config.js'
import { parseAuthority, parseCidr } from './network.js'

export type { RemoteProvider, RemoteProviderController, RemoteProviderState, RemoteProviderStatus } from './remote.js'
export {
  JsonRemoteProviderStore,
  RemoteProviderCoordinator,
  parseRemoteProviderState,
  settleRemoteResources,
  terminateRemoteProcess,
} from './remote.js'
export type { RemoteProviderStore } from './remote.js'

export { FunnelController, funnelExecutable } from './funnel.js'
export type { FunnelControllerOptions, FunnelState, FunnelStatus } from './funnel.js'
export { CpolarController } from './cpolar.js'
export type { CpolarControllerOptions, CpolarState, CpolarStatus } from './cpolar.js'
export { CpolarComponentManager, CPOLAR_COMPONENT_RELEASE } from './cpolar-component.js'
export type { CpolarComponentStatus } from './cpolar-component.js'
export { FrpComponentManager } from './frp-component.js'
export type { FrpComponentStatus } from './frp-component.js'
export { FrpConfigStore } from './frp-config.js'
export type { FrpConfigurationStatus, FrpSettings } from './frp-config.js'
export { FrpController } from './frp.js'
export type { FrpControllerOptions, FrpState, FrpStatus } from './frp.js'

/** Amadeus remote state root under the DSH home. */
export function amadeusRemoteDirectory(): string {
  return join(homedir(), '.dsh', 'amadeus', 'remote')
}

/** Amadeus stable instance id: SHA-256 of the state file, matching LAN config. */
export function amadeusInstanceId(stateFile: string): string {
  return createHash('sha256').update(stateFile).digest('hex')
}

/**
 * Build an Amadeus remote gateway config: loopback ephemeral listener behind
 * the public tunnel, HTTPS public authority, digest-isolated device store.
 * Mirrors dsh-mobile's `remoteGatewayConfig` with Amadeus-owned paths.
 */
export function amadeusRemoteGatewayConfig(
  template: ResolvedGatewayConfig,
  publicOrigin: string,
  stateFile: string,
  instanceId: string,
  listenPort = 0,
): ResolvedGatewayConfig {
  const origin = new URL(publicOrigin)
  if (origin.protocol !== 'https:' || origin.username !== '' || origin.password !== ''
    || origin.pathname !== '/' || origin.search !== '' || origin.hash !== '') {
    throw new Error('remote public origin must be an HTTPS origin')
  }
  const publicAuthority = origin.port === '' ? `${origin.hostname}:443` : origin.host
  // 保留 pairingCaFile：远程网关也提供 /amadeus/ca.cer（App bootstrap 拉 CA）。
  // gateway.start() 校验 pairingCaFile 的 fingerprint === instanceId，所以远程
  // instanceId 必须是 CA fingerprint（LAN 网关的 instanceId 就是 CA fingerprint）。
  const { pairingCaFile: _pairingCaFile, ...shared } = template
  return Object.freeze({
    ...shared,
    ...(template.pairingCaFile === undefined ? {} : { pairingCaFile: template.pairingCaFile }),
    listenHost: '127.0.0.1',
    listenPort,
    authorities: Object.freeze([parseAuthority(publicAuthority)]),
    allowedCidrs: Object.freeze([parseCidr('127.0.0.0/8')]),
    stateFile,
    instanceId,
    tls: Object.freeze({ mode: 'disabled' }),
    publicTls: true,
    discovery: false,
  })
}

/** Amadeus-owned device store used by every remote gateway (not the LAN one). */
export function amadeusRemoteDeviceFile(): string {
  return join(amadeusRemoteDirectory(), 'devices.json')
}

/** Amadeus-owned per-provider on/off control files. */
export function amadeusRemoteControlFiles(): Record<'tailscale' | 'cpolar' | 'frp', string> {
  return {
    tailscale: join(amadeusRemoteDirectory(), 'tailscale', 'control.json'),
    cpolar: join(amadeusRemoteDirectory(), 'cpolar', 'control.json'),
    frp: join(amadeusRemoteDirectory(), 'frp', 'control.json'),
  }
}

/** Amadeus-owned provider selection store. */
export function amadeusRemoteProviderFile(): string {
  return join(amadeusRemoteDirectory(), 'provider.json')
}

/** Amadeus-owned FRP settings root (settings.json + frpc.toml). */
export function amadeusFrpConfigDirectory(): string {
  return join(amadeusRemoteDirectory(), 'frp', 'config')
}

/** Amadeus-owned cpolar component storage (downloads cpolar.exe). */
export function amadeusCpolarStateDirectory(): string {
  return join(amadeusRemoteDirectory(), 'cpolar')
}

/** Amadeus-owned tailscale node state directory. */
export function amadeusTailscaleStateDirectory(): string {
  return join(amadeusRemoteDirectory(), 'tailscale')
}

/** Resolve the first-run provider without leaking the dsh-mobile env. */
export function amadeusDefaultRemoteProvider(environment: NodeJS.ProcessEnv): 'tailscale' | 'cpolar' | 'frp' {
  const value = environment.AMADEUS_REMOTE_PROVIDER ?? 'frp'
  if (value !== 'tailscale' && value !== 'cpolar' && value !== 'frp') {
    throw new Error('AMADEUS_REMOTE_PROVIDER must be tailscale, cpolar, or frp')
  }
  return value
}
