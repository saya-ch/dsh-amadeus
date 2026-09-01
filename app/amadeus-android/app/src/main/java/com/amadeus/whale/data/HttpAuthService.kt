package com.amadeus.whale.data

import com.amadeus.whale.domain.AuthService
import com.amadeus.whale.domain.DeviceCredential
import com.amadeus.whale.domain.PairResult
import com.amadeus.whale.pairing.AmadeusAuthClient
import com.amadeus.whale.pairing.AuthResult
import com.amadeus.whale.pairing.GatewayOrigin
import com.amadeus.whale.pairing.PairingScanTarget
import okhttp3.OkHttpClient

/**
 * 配对适配器：domain AuthService ← pairing 层（架构 3.7 收敛）。
 * 配对/恢复成功后返回带凭据的 OkHttpClient 供 Repository 使用。
 */
class HttpAuthService(
  private val authClient: AmadeusAuthClient,
) : AuthService {

  /** 配对成功后存下的真实 client（供 Repository 构造）。 */
  data class SessionClient(val origin: GatewayOrigin, val client: OkHttpClient)

  @Volatile
  private var current: SessionClient? = null

  /** 最近一次成功配对的 session client。 */
  fun currentSession(): SessionClient? = current

  override suspend fun pair(origin: String): PairResult {
    return try {
      val result = authClient.pair(PairingScanTarget.parse(origin))
      when (result) {
        is AuthResult.Success -> {
          current = SessionClient(result.origin, result.client)
          PairResult.Success(
            DeviceCredential(
              instanceId = result.instanceId,
              deviceToken = result.instanceId, // domain 层不需要 deviceToken 细节
              deviceExpiresAt = result.sessionExpiresAt,
              caCertificate = ByteArray(0),
              origin = result.origin.serialized,
            ),
          )
        }
        is AuthResult.Failure -> PairResult.Failure(result.message)
      }
    } catch (error: IllegalArgumentException) {
      PairResult.Failure(error.message ?: "配对输入无法解析")
    } catch (error: Exception) {
      PairResult.Failure(error.message ?: "配对失败")
    }
  }

  override suspend fun restore(origin: String): Any? {
    return try {
      val gateway = GatewayOrigin.parse(origin)
      val result = authClient.restore(gateway)
      when (result) {
        is AuthResult.Success -> {
          current = SessionClient(result.origin, result.client)
          result.client
        }
        is AuthResult.Failure -> null
      }
    } catch (error: Exception) {
      null
    }
  }

  override suspend fun disconnect(origin: String) {
    try {
      authClient.clear(GatewayOrigin.parse(origin))
    } catch (_: Exception) {
    }
    current = null
  }
}
