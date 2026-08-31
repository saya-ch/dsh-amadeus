package com.amadeus.whale.pairing

data class PairingScanTarget(val origin: GatewayOrigin, val instanceId: String, val token: String) {
  companion object {
    private val PAIR_URL = Regex("^https://([^/?#]+)/mobile-access/pair#instance=([a-f0-9]{64})&token=([A-Za-z0-9_-]{43})$")

    fun parse(raw: String): PairingScanTarget {
      val trimmed = raw.trim()
      PAIR_URL.matchEntire(trimmed)?.let { m ->
        val origin = GatewayOrigin.parse("https://${m.groupValues[1]}")
        return PairingScanTarget(origin, m.groupValues[2], m.groupValues[3])
      }
      // Fall back to a bare pairing key: origin is unknown until paired, so require it separately.
      PairingKey.parse(trimmed) // throws on malformed; validates instanceId/token shapes
      throw IllegalArgumentException("a bare appKey needs the gateway address; use a pairing URL or scan")
    }
  }
}
