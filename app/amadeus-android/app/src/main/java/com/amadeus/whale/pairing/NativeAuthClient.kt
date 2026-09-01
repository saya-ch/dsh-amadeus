package com.amadeus.whale.pairing

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class NativeSession(
  val instanceId: String,
  val deviceId: String,
  val deviceToken: String?,
  val deviceExpiresAt: Long?,
  val sessionToken: String,
  val csrfToken: String,
  val sessionExpiresAt: Long,
)

enum class NativeAuthFailureKind {
  PAIRING_EXPIRED,
  DEVICE_LIMIT,
  RATE_LIMITED,
  TIMEOUT,
  TLS,
  NETWORK,
  SERVER_UNAVAILABLE,
  INVALID_RESPONSE,
}

class NativeAuthException(val kind: NativeAuthFailureKind, message: String, cause: Throwable? = null) :
  Exception(message, cause)

val PAIR_KEYS: Set<String> = setOf(
  "instanceId",
  "deviceId",
  "deviceToken",
  "deviceExpiresAt",
  "sessionToken",
  "csrfToken",
  "sessionExpiresAt",
)

val RENEW_KEYS: Set<String> = setOf(
  "instanceId",
  "deviceId",
  "sessionToken",
  "csrfToken",
  "sessionExpiresAt",
)

private val INSTANCE_ID_REGEX = Regex("^[a-f0-9]{64}$")
private val DEVICE_ID_REGEX = Regex("^[a-f0-9]{32}$")
private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{43}$")

fun parseNativeSessionResponse(json: String, expectedInstanceId: String, keys: Set<String>): NativeSession {
  try {
    val element = Json.parseToJsonElement(json)
    val obj = element.jsonObject
    val actualKeys = obj.keys
    if (actualKeys != keys) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "keys mismatch: expected $keys but was $actualKeys",
      )
    }
    val instanceId = obj["instanceId"]?.jsonPrimitive?.content ?: ""
    if (instanceId != expectedInstanceId) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "instanceId mismatch: expected $expectedInstanceId but was $instanceId",
      )
    }
    if (!INSTANCE_ID_REGEX.matches(instanceId)) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "instanceId pattern invalid",
      )
    }
    val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: ""
    if (!DEVICE_ID_REGEX.matches(deviceId)) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "deviceId pattern invalid: $deviceId",
      )
    }
    val isPair = keys.contains("deviceToken")
    var deviceToken: String? = null
    var deviceExpiresAt: Long? = null
    if (isPair) {
      deviceToken = obj["deviceToken"]?.jsonPrimitive?.content ?: ""
      if (!TOKEN_REGEX.matches(deviceToken)) {
        throw NativeAuthException(
          NativeAuthFailureKind.INVALID_RESPONSE,
          "deviceToken pattern invalid",
        )
      }
      val devExpElement = obj["deviceExpiresAt"]
        ?: throw NativeAuthException(
          NativeAuthFailureKind.INVALID_RESPONSE,
          "missing deviceExpiresAt",
        )
      deviceExpiresAt = try {
        devExpElement.jsonPrimitive.long
      } catch (_: Exception) {
        throw NativeAuthException(
          NativeAuthFailureKind.INVALID_RESPONSE,
          "deviceExpiresAt invalid",
        )
      }
    }
    val sessionToken = obj["sessionToken"]?.jsonPrimitive?.content ?: ""
    if (!TOKEN_REGEX.matches(sessionToken)) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "sessionToken pattern invalid",
      )
    }
    val csrfToken = obj["csrfToken"]?.jsonPrimitive?.content ?: ""
    if (!TOKEN_REGEX.matches(csrfToken)) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "csrfToken pattern invalid",
      )
    }
    val sessionExpElement = obj["sessionExpiresAt"]
      ?: throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "missing sessionExpiresAt",
      )
    val sessionExpiresAt = try {
      sessionExpElement.jsonPrimitive.long
    } catch (_: Exception) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "sessionExpiresAt invalid",
      )
    }
    val now = System.currentTimeMillis()
    if (sessionExpiresAt <= now) {
      throw NativeAuthException(
        NativeAuthFailureKind.INVALID_RESPONSE,
        "sessionExpiresAt not in future: $sessionExpiresAt <= $now",
      )
    }
    if (isPair) {
      val devExp = deviceExpiresAt!!
      if (devExp < sessionExpiresAt) {
        throw NativeAuthException(
          NativeAuthFailureKind.INVALID_RESPONSE,
          "deviceExpiresAt < sessionExpiresAt",
        )
      }
    }
    return NativeSession(
      instanceId = instanceId,
      deviceId = deviceId,
      deviceToken = deviceToken,
      deviceExpiresAt = deviceExpiresAt,
      sessionToken = sessionToken,
      csrfToken = csrfToken,
      sessionExpiresAt = sessionExpiresAt,
    )
  } catch (e: NativeAuthException) {
    throw e
  } catch (e: Exception) {
    throw NativeAuthException(
      NativeAuthFailureKind.INVALID_RESPONSE,
      "invalid json: ${e.message}",
      e,
    )
  }
}

class NativeAuthClient(
  private val bootstrapClient: OkHttpClient,
  private val sessionClientFactory: (caDer: ByteArray, instanceId: String) -> OkHttpClient,
) {
  private val jsonType = "application/json".toMediaType()

  /** 远程隧道（cpolar/tailscale 域名）：公网侧是隧道服务商的受信证书（Let's Encrypt），
   *  不是网关自签 CA——pin 会失败。改用系统 CA 信任（跳过 hostname，因为隧道域名
   *  与证书 SAN 可能不同）。LAN（IP）保持 pin。 */
  private fun remoteClient(): OkHttpClient {
    val tm = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
      override fun checkServerTrusted(
        chain: Array<out java.security.cert.X509Certificate>?,
        authType: String?,
      ) {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, SecureRandom())
        val delegate = context.socketFactory as javax.net.ssl.SSLSocketFactory
        // 用系统默认 TrustManager 验证链
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        val tmDefault = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        tmDefault.checkServerTrusted(chain, authType)
      }
      override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(tm), SecureRandom())
    return OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .sslSocketFactory(context.socketFactory, tm)
      .hostnameVerifier { _, _ -> true }
      .build()
  }

  private fun isRemoteHost(host: String): Boolean {
    // IP（IPv4/IPv6）视为 LAN 直连；其余（域名）视为远程隧道
    val trimmed = host.trim('[', ']')
    val ipv4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(trimmed)
    val ipv6 = trimmed.contains(':')
    return !ipv4 && !ipv6
  }

  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("${origin.serialized}/amadeus/ca.cer").get().build()
    try {
      bootstrapClient.newCall(request).execute().use { resp ->
        if (resp.code != 200) {
          throw NativeAuthException(
            NativeAuthFailureKind.SERVER_UNAVAILABLE,
            "ca.cer returned ${resp.code}",
          )
        }
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        if (bytes.size > 16 * 1024) {
          throw NativeAuthException(
            NativeAuthFailureKind.INVALID_RESPONSE,
            "ca.cer too large: ${bytes.size}",
          )
        }
        try {
          CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
        } catch (error: Exception) {
          throw NativeAuthException(
            NativeAuthFailureKind.INVALID_RESPONSE,
            "ca.cer is not X.509",
            error,
          )
        }
        bytes
      }
    } catch (error: NativeAuthException) {
      throw error
    } catch (error: SSLException) {
      throw NativeAuthException(NativeAuthFailureKind.TLS, "tls failure: ${error.message}", error)
    } catch (error: java.net.SocketTimeoutException) {
      throw NativeAuthException(NativeAuthFailureKind.TIMEOUT, "timeout: ${error.message}", error)
    } catch (error: InterruptedIOException) {
      throw NativeAuthException(NativeAuthFailureKind.TIMEOUT, "timeout: ${error.message}", error)
    } catch (error: ConnectException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "unreachable: ${error.message}", error)
    } catch (error: IOException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "io: ${error.message}", error)
    }
  }

  suspend fun pair(
    origin: GatewayOrigin,
    token: String,
    caDer: ByteArray,
    instanceId: String,
    label: String? = null,
  ): NativeSession = withContext(Dispatchers.IO) {
    val payload = buildJsonObject {
      put("token", token)
      if (label != null) put("label", label)
    }.toString()
    val request = Request.Builder()
      .url("${origin.serialized}/amadeus/auth/native-pair")
      .post(payload.toRequestBody(jsonType))
      .build()
    postForSession(request, instanceId, PAIR_KEYS, caDer, origin)
  }

  suspend fun renew(
    origin: GatewayOrigin,
    deviceToken: String,
    caDer: ByteArray,
    instanceId: String,
  ): NativeSession = withContext(Dispatchers.IO) {
    val payload = buildJsonObject { put("deviceToken", deviceToken) }.toString()
    val request = Request.Builder()
      .url("${origin.serialized}/amadeus/auth/native-renew")
      .post(payload.toRequestBody(jsonType))
      .build()
    postForSession(request, instanceId, RENEW_KEYS, caDer, origin)
  }

  private fun postForSession(
    request: Request,
    expectedInstanceId: String,
    keys: Set<String>,
    caDer: ByteArray,
    origin: GatewayOrigin,
  ): NativeSession {
    // 远程隧道（域名）：公网侧证书是隧道服务商签发的（受系统 CA 信任），pin 网关自签 CA 会失败
    val isRemote = isRemoteHost(origin.host)
    val sessionClient: OkHttpClient = try {
      if (isRemote) remoteClient()
      else sessionClientFactory(caDer, expectedInstanceId)
    } catch (e: SecurityException) {
      throw NativeAuthException(NativeAuthFailureKind.TLS, "tls setup failed: ${e.message}", e)
    } catch (e: SSLException) {
      throw NativeAuthException(NativeAuthFailureKind.TLS, "tls setup failed: ${e.message}", e)
    } catch (e: Exception) {
      throw NativeAuthException(NativeAuthFailureKind.INVALID_RESPONSE, "client factory failed: ${e.message}", e)
    }
    try {
      sessionClient.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw mapHttpError(resp.code)
        }
        val body = resp.body?.string().orEmpty()
        return parseNativeSessionResponse(body, expectedInstanceId, keys)
      }
    } catch (error: NativeAuthException) {
      throw error
    } catch (error: SSLException) {
      throw NativeAuthException(NativeAuthFailureKind.TLS, "tls failure: ${error.message}", error)
    } catch (error: java.net.SocketTimeoutException) {
      throw NativeAuthException(NativeAuthFailureKind.TIMEOUT, "timeout: ${error.message}", error)
    } catch (error: InterruptedIOException) {
      throw NativeAuthException(NativeAuthFailureKind.TIMEOUT, "timeout: ${error.message}", error)
    } catch (error: ConnectException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "unreachable: ${error.message}", error)
    } catch (error: IOException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "io: ${error.message}", error)
    }
  }

  private fun mapHttpError(code: Int): NativeAuthException {
    val kind = when (code) {
      401, 403 -> NativeAuthFailureKind.PAIRING_EXPIRED
      409 -> NativeAuthFailureKind.DEVICE_LIMIT
      429 -> NativeAuthFailureKind.RATE_LIMITED
      in 500..599 -> NativeAuthFailureKind.SERVER_UNAVAILABLE
      else -> NativeAuthFailureKind.INVALID_RESPONSE
    }
    return NativeAuthException(kind, "http $code")
  }
}

fun trustAllClient(): OkHttpClient {
  val tm = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
  }
  val context = SSLContext.getInstance("TLS")
  context.init(null, arrayOf<TrustManager>(tm), SecureRandom())
  return OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .sslSocketFactory(context.socketFactory, tm)
    .hostnameVerifier { _, _ -> true }
    .build()
}
