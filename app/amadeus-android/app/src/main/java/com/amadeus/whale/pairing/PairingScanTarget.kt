package com.amadeus.whale.pairing

data class PairingScanTarget(val origin: GatewayOrigin, val instanceId: String, val token: String) {
  companion object {
    private val PAIR_URL = Regex("^https://([^/?#]+)/mobile-access/pair#instance=([a-f0-9]{64})&token=([A-Za-z0-9_-]{43})$")

    fun parse(raw: String): PairingScanTarget {
      val trimmed = raw.trim()
      // (a) A bare origin? It has a gateway address but no pairing key yet.
      val isBareOrigin = try {
        GatewayOrigin.parse(trimmed)
        true
      } catch (error: IllegalArgumentException) {
        false
      }
      if (isBareOrigin) throw IllegalArgumentException("origin without pairing key: $trimmed")
      // (b) A full pairing URL?
      val match = PAIR_URL.matchEntire(trimmed)
      if (match != null) {
        val origin = GatewayOrigin.parse("https://${match.groupValues[1]}")
        return PairingScanTarget(origin, match.groupValues[2], match.groupValues[3])
      }
      // (c) A bare appKey? It has no gateway address, so the UI must combine one.
      PairingKey.parse(trimmed)
      throw IllegalArgumentException("appKey without gateway origin; please provide pairing URL or scan QR")
    }
  }
}
