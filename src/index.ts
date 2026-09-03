/** Independent Amadeus plugin using DSH Mobile for authenticated transport. */
export { apply, Config, inject, name } from './amadeus-plugin.js'
export { AMADEUS_EXTENSION_ID, createAmadeusExtension } from './amadeus-extension.js'
export type { AmadeusGatewayOptions, AmadeusReport, AmadeusSessionSummary } from './amadeus-extension.js'
export { AMADEUS_MODE_ID } from './amadeus-mode.js'
export {
  ensureAmadeusTag,
  hasAmadeusTag,
  parseAmadeusSegments,
  parseAmadeusTag,
  stripAllTags,
  stripAmadeusTag,
} from './amadeus-tags.js'
export {
  availableLanNetworks,
  ensureManagedCa,
  materializeManagedSetup,
  parseManagedSetup,
  preferredLanInterfaceNames,
  refreshManagedServerCertificate,
  selectLanNetwork,
} from './managed-setup.js'
export type { LanNetwork, ManagedSetup } from './managed-setup.js'
export type {
  AmadeusBgm, AmadeusSegment, AmadeusSfx,
  AmadeusSprite, AmadeusTag, AmadeusVoice, AmadeusWindow,
} from './amadeus-tags.js'
