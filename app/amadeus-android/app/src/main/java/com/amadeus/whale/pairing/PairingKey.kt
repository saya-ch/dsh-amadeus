package com.amadeus.whale.pairing

data class PairingKey(val instanceId: String, val token: String) {
  companion object {
    private val APP_KEY = Regex("^dsh1\\.([a-f0-9]{64})\\.([A-Za-z0-9_-]{43})$")

    fun parse(raw: String): PairingKey {
      val match = APP_KEY.matchEntire(raw.trim())
        ?: throw IllegalArgumentException("not an amadeus pairing key")
      return PairingKey(match.groupValues[1], match.groupValues[2])
    }
  }
}
