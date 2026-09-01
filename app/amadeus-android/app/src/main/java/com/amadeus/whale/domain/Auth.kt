package com.amadeus.whale.domain

/** 设备凭据（架构 3.7：CredentialStore domain 接口，data 层实现）。 */
data class DeviceCredential(
  val instanceId: String,
  val deviceToken: String,
  val deviceExpiresAt: Long,
  val caCertificate: ByteArray,
  val origin: String,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DeviceCredential) return false
    return instanceId == other.instanceId && deviceToken == other.deviceToken &&
      deviceExpiresAt == other.deviceExpiresAt && caCertificate.contentEquals(other.caCertificate) &&
      origin == other.origin
  }

  override fun hashCode(): Int {
    var r = instanceId.hashCode()
    r = 31 * r + deviceToken.hashCode()
    r = 31 * r + deviceExpiresAt.hashCode()
    r = 31 * r + caCertificate.contentHashCode()
    r = 31 * r + origin.hashCode()
    return r
  }
}

interface CredentialStore {
  fun save(origin: String, credential: DeviceCredential): Boolean
  fun load(origin: String): DeviceCredential?
  fun clear(origin: String)
}

/** 配对结果。 */
sealed class PairResult {
  data class Success(val credential: DeviceCredential) : PairResult()
  data object Cancelled : PairResult()
  data class Failure(val message: String) : PairResult()
}

/** 认证服务（架构 3.7：配对/恢复/断开，domain 接口）。 */
interface AuthService {
  /** 配对（扫码/native-pair），成功后保存凭据。 */
  suspend fun pair(origin: String): PairResult

  /** 恢复已保存网关的会话 client（返回 OkHttpClient 或 null）。 */
  suspend fun restore(origin: String): Any? // OkHttpClient，避免 domain 依赖 OkHttp

  /** 断开（清除凭据）。 */
  suspend fun disconnect(origin: String)
}
