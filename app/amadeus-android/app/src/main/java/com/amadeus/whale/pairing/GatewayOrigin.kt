package com.amadeus.whale.pairing

data class GatewayOrigin(val host: String, val port: Int) {
  val serialized: String
    get() = if (port == 443) "https://$host" else "https://$host:$port"

  companion object {
    fun parse(raw: String): GatewayOrigin {
      val url = try {
        java.net.URI(raw)
      } catch (error: Exception) {
        throw IllegalArgumentException("invalid origin: $raw", error)
      }
      if (url.scheme != "https") throw IllegalArgumentException("origin must use https")
      if (url.rawPath.isNotEmpty() && url.rawPath != "/") throw IllegalArgumentException("origin must not have a path")
      if (url.rawQuery != null) throw IllegalArgumentException("origin must not have a query")
      if (url.rawFragment != null) throw IllegalArgumentException("origin must not have a fragment")
      if (url.rawUserInfo != null) throw IllegalArgumentException("origin must not have credentials")
      val host = url.host ?: throw IllegalArgumentException("origin must have a host")
      val port = if (url.port == -1) 443 else url.port
      return GatewayOrigin(host, port)
    }
  }
}
