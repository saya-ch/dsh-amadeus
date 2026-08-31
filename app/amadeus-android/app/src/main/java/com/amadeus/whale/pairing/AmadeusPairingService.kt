package com.amadeus.whale.pairing

interface PairingService {
  suspend fun pair(keyInput: String): AuthResult
  suspend fun restore(origin: GatewayOrigin): AuthResult
}

class AmadeusPairingService(
  private val authClient: AmadeusAuthClient,
) : PairingService {
  override suspend fun pair(keyInput: String): AuthResult {
    val target = try {
      PairingScanTarget.parse(keyInput)
    } catch (error: IllegalArgumentException) {
      return AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, "无法解析配对密钥；请使用带网关地址的配对 URL 或扫码", error)
    } catch (error: Exception) {
      return AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, "无法解析配对密钥；请使用带网关地址的配对 URL 或扫码", error)
    }
    return authClient.pair(target)
  }

  override suspend fun restore(origin: GatewayOrigin): AuthResult = authClient.restore(origin)
}
