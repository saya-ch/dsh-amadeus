import type { DeviceSummary } from './access.js'

/** Client-facing device row consumed by the desktop control panel. */
export interface AmadeusControlDevice {
  readonly id: string
  readonly label: string
  readonly lastSeenAt: number
}

/** Real desktop control state: the LAN running flag plus live gateway data. */
export interface AmadeusControlState {
  readonly running: boolean
  readonly origin?: string
  readonly devices: readonly AmadeusControlDevice[]
  readonly pairingOpen: boolean
}

/** Minimal real gateway surface the control routes read from. */
export interface AmadeusGatewayControl {
  readonly origin: string
  devices(): readonly DeviceSummary[]
  pairingStatus(): { open: boolean; expiresAt?: number }
}

export interface AmadeusControlRoutesOptions {
  readonly isRunning: () => boolean
  readonly gateway: () => AmadeusGatewayControl | undefined
}

/** Project the running gateway onto the desktop control-panel endpoints. */
export class AmadeusControlRoutes {
  constructor(private readonly options: AmadeusControlRoutesOptions) {}

  /** GET /api/amadeus/control — running flag plus real devices and pairing window. */
  controlGet(): { status: number; body: string } {
    const gateway = this.options.gateway()
    const state: AmadeusControlState = {
      running: this.options.isRunning(),
      devices: gateway?.devices().map(device => ({
        id: device.id,
        label: device.label,
        lastSeenAt: device.lastSeenAt,
      })) ?? [],
      pairingOpen: gateway?.pairingStatus().open ?? false,
      ...(gateway === undefined ? {} : { origin: gateway.origin }),
    }
    return { status: 200, body: JSON.stringify(state) }
  }
}