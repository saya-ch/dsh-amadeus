package com.amadeus.whale.pairing

import okhttp3.OkHttpClient

interface NativeAuthGateway {
  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray
  suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String? = null): NativeSession
  suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession
}

fun NativeAuthClient.toGateway(): NativeAuthGateway = object : NativeAuthGateway {
  override suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray = this@toGateway.fetchPairingCa(origin)
  override suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String?): NativeSession =
    this@toGateway.pair(origin, token, caDer, instanceId, label)
  override suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession =
    this@toGateway.renew(origin, deviceToken, caDer, instanceId)
}

sealed class AuthResult {
  data class Success(
    val origin: GatewayOrigin,
    val instanceId: String,
    val deviceId: String,
    val client: OkHttpClient,
    val sessionExpiresAt: Long,
  ) : AuthResult()

  data class Failure(val kind: NativeAuthFailureKind, val message: String, val cause: Throwable? = null) : AuthResult()
}

class AmadeusAuthClient(
  private val nativeAuth: NativeAuthGateway,
  private val credentialStore: DeviceCredentialStore,
  private val baseClient: OkHttpClient,
) {

  constructor(
    nativeAuth: NativeAuthClient,
    credentialStore: DeviceCredentialStore,
    baseClient: OkHttpClient,
  ) : this(nativeAuth.toGateway(), credentialStore, baseClient)

  suspend fun pair(target: PairingScanTarget, label: String? = null): AuthResult {
    return try {
      val caDer = nativeAuth.fetchPairingCa(target.origin)
      try {
        PinnedTls.validateCertificate(caDer, target.instanceId)
      } catch (e: SecurityException) {
        return AuthResult.Failure(NativeAuthFailureKind.TLS, e.message ?: "tls validation failed", e)
      } catch (e: Exception) {
        return AuthResult.Failure(NativeAuthFailureKind.TLS, e.message ?: "tls validation failed", e)
      }
      val session = nativeAuth.pair(target.origin, target.token, caDer, target.instanceId, label)
      val credential = DeviceCredential(
        instanceId = session.instanceId,
        deviceToken = session.deviceToken!!,
        deviceExpiresAt = session.deviceExpiresAt!!,
        caCertificate = caDer,
        origin = target.origin.serialized,
      )
      credentialStore.save(target.origin.serialized, credential)
      val client = buildSessionClient(target.origin, session.sessionToken, session.csrfToken, caDer, session.instanceId)
      AuthResult.Success(
        origin = target.origin,
        instanceId = session.instanceId,
        deviceId = session.deviceId,
        client = client,
        sessionExpiresAt = session.sessionExpiresAt,
      )
    } catch (error: NativeAuthException) {
      try {
        credentialStore.clear(target.origin.serialized)
      } catch (_: Exception) {
      }
      AuthResult.Failure(error.kind, error.message ?: "pair failed", error)
    } catch (error: SecurityException) {
      try {
        credentialStore.clear(target.origin.serialized)
      } catch (_: Exception) {
      }
      AuthResult.Failure(NativeAuthFailureKind.TLS, error.message ?: "tls validation failed", error)
    } catch (error: Exception) {
      AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, error.message ?: "pair failed", error)
    }
  }

  suspend fun restore(origin: GatewayOrigin): AuthResult {
    val credential = credentialStore.load(origin.serialized)
    if (credential == null) {
      return AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, "no saved credential")
    }
    try {
      PinnedTls.validateCertificate(credential.caCertificate, credential.instanceId)
    } catch (e: SecurityException) {
      credentialStore.clear(origin.serialized)
      return AuthResult.Failure(NativeAuthFailureKind.TLS, e.message ?: "tls validation failed", e)
    } catch (e: Exception) {
      credentialStore.clear(origin.serialized)
      return AuthResult.Failure(NativeAuthFailureKind.TLS, e.message ?: "tls validation failed", e)
    }
    return try {
      val session = nativeAuth.renew(origin, credential.deviceToken, credential.caCertificate, credential.instanceId)
      val updated = credential.copy(
        deviceExpiresAt = session.deviceExpiresAt ?: credential.deviceExpiresAt,
        caCertificate = credential.caCertificate,
      )
      credentialStore.save(origin.serialized, updated)
      val client = buildSessionClient(origin, session.sessionToken, session.csrfToken, credential.caCertificate, session.instanceId)
      AuthResult.Success(
        origin = origin,
        instanceId = session.instanceId,
        deviceId = session.deviceId,
        client = client,
        sessionExpiresAt = session.sessionExpiresAt,
      )
    } catch (error: NativeAuthException) {
      credentialStore.clear(origin.serialized)
      AuthResult.Failure(error.kind, error.message ?: "renew failed", error)
    } catch (error: SecurityException) {
      credentialStore.clear(origin.serialized)
      AuthResult.Failure(NativeAuthFailureKind.TLS, error.message ?: "tls validation failed", error)
    } catch (error: Exception) {
      credentialStore.clear(origin.serialized)
      AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, error.message ?: "renew failed", error)
    }
  }

  fun createSessionClient(origin: GatewayOrigin): OkHttpClient? {
    val credential = credentialStore.load(origin.serialized) ?: return null
    try {
      PinnedTls.validateCertificate(credential.caCertificate, credential.instanceId)
    } catch (_: Exception) {
      return null
    }
    return try {
      val jar = AuthCookieJar()
      // 远程隧道：公网证书是隧道服务商的（系统 CA 信任），不能用 pin 的 client
      val client = if (isRemoteHost(origin.host)) {
        baseClient.newBuilder().hostnameVerifier { _, _ -> true }.build()
      } else {
        try {
          baseClient.newBuilder()
            .sslSocketFactory(
              PinnedTls.socketFactory(credential.caCertificate, credential.instanceId),
              PinnedTls.trustManager(credential.caCertificate, credential.instanceId),
            )
            // 远程隧道域名与证书 SAN（LAN IP）不匹配：CA pin 已保证真实性，跳过主机名验证
            .hostnameVerifier { _, _ -> true }
            .build()
        } catch (_: Exception) {
          return null
        }
      }
      buildAuthClient(client, jar)
    } catch (_: Exception) {
      null
    }
  }

  fun clear(origin: GatewayOrigin) {
    credentialStore.clear(origin.serialized)
  }

  fun buildSessionClient(origin: GatewayOrigin, sessionToken: String, csrfToken: String, caDer: ByteArray, instanceId: String): OkHttpClient {
    val jar = AuthCookieJar()
    jar.store("amw_session=$sessionToken; Path=/; Secure; HttpOnly", "amw_csrf=$csrfToken; Path=/; Secure")
    // 远程隧道：系统 CA 信任（公网证书是隧道服务商签发的）；LAN：pin 网关自签 CA
    val client = if (isRemoteHost(origin.host)) {
      baseClient.newBuilder().hostnameVerifier { _, _ -> true }.build()
    } else {
      baseClient.newBuilder()
        .sslSocketFactory(PinnedTls.socketFactory(caDer, instanceId), PinnedTls.trustManager(caDer, instanceId))
        .hostnameVerifier { _, _ -> true }
        .build()
    }
    return buildAuthClient(client, jar)
  }

  private fun isRemoteHost(host: String): Boolean {
    val trimmed = host.trim('[', ']')
    val ipv4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(trimmed)
    val ipv6 = trimmed.contains(':')
    return !ipv4 && !ipv6
  }
}
