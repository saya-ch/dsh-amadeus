package com.amadeus.whale.pairing

import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeAuthClientTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() { server = MockWebServer(); server.start() }

  @After fun tearDown() { server.shutdown() }

  private fun origin(): GatewayOrigin {
    val url = server.url("/")
    return GatewayOrigin(url.host, url.port)
  }

  private fun schemeRewritingClient(): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val req = chain.request()
        val newUrl = req.url.newBuilder().scheme("http").build()
        chain.proceed(req.newBuilder().url(newUrl).build())
      }
      .build()

  private fun bootstrapClient(): OkHttpClient = schemeRewritingClient()

  private fun sessionFactory(): (ByteArray, String) -> OkHttpClient = { _, _ -> schemeRewritingClient() }

  private fun client(): NativeAuthClient = NativeAuthClient(bootstrapClient(), sessionFactory())

  private fun pairBody(
    instanceId: String = "a".repeat(64),
    deviceId: String = "b".repeat(32),
    deviceToken: String = "C".repeat(43),
    deviceExpiresAt: Long = System.currentTimeMillis() + 3600_000,
    sessionToken: String = "D".repeat(43),
    csrfToken: String = "e".repeat(43),
    sessionExpiresAt: Long = System.currentTimeMillis() + 3600_000,
  ): String =
    """{"instanceId":"$instanceId","deviceId":"$deviceId","deviceToken":"$deviceToken","deviceExpiresAt":$deviceExpiresAt,"sessionToken":"$sessionToken","csrfToken":"$csrfToken","sessionExpiresAt":$sessionExpiresAt}"""

  private fun renewBody(
    instanceId: String = "a".repeat(64),
    deviceId: String = "b".repeat(32),
    sessionToken: String = "D".repeat(43),
    csrfToken: String = "e".repeat(43),
    sessionExpiresAt: Long = System.currentTimeMillis() + 3600_000,
  ): String =
    """{"instanceId":"$instanceId","deviceId":"$deviceId","sessionToken":"$sessionToken","csrfToken":"$csrfToken","sessionExpiresAt":$sessionExpiresAt}"""

  @Test fun fetchPairingCaReturnsDerBytes() = runTest {
    val der = generateTestCaDer()
    server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pkix-cert").setBody(okio.Buffer().write(der)))
    val ca = client().fetchPairingCa(origin())
    assertEquals(der.size, ca.size)
    assertTrue(ca.contentEquals(der))
  }

  @Test fun fetchPairingCaInvalidCertIsInvalidResponse() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pkix-cert").setBody("not-a-cert"))
    val ex = runCatching { client().fetchPairingCa(origin()) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  private fun generateTestCaDer(): ByteArray {
    if (java.security.Security.getProvider("BC") == null) {
      java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }
    val kpg = java.security.KeyPairGenerator.getInstance("EC")
    kpg.initialize(256, java.security.SecureRandom())
    val kp = kpg.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
      javax.security.auth.x500.X500Principal("CN=amadeus-test"),
      java.math.BigInteger.ONE,
      java.util.Date(now - 1000),
      java.util.Date(now + 365L * 24 * 3600 * 1000),
      javax.security.auth.x500.X500Principal("CN=amadeus-test"),
      kp.public,
    )
    builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, org.bouncycastle.asn1.x509.BasicConstraints(true))
    val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(kp.private)
    val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
    return cert.encoded
  }

  @Test fun fetchPairingCaTooLargeIsInvalidResponse() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/pkix-cert").setBody(okio.Buffer().write(ByteArray(16 * 1024 + 1))))
    val ex = runCatching { client().fetchPairingCa(origin()) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun fetchPairingCaNon200MapsToServerUnavailable() = runTest {
    server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
    val ex = runCatching { client().fetchPairingCa(origin()) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.SERVER_UNAVAILABLE, ex!!.kind)
  }

  @Test fun pairParsesValidSession() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody()).addHeader("Content-Type", "application/json"))
    val session = client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64))
    assertEquals("a".repeat(64), session.instanceId)
    assertEquals("b".repeat(32), session.deviceId)
    assertEquals("C".repeat(43), session.deviceToken)
    assertEquals("D".repeat(43), session.sessionToken)
  }

  @Test fun renewParsesValidSession() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(renewBody()).addHeader("Content-Type", "application/json"))
    val session = client().renew(origin(), "C".repeat(43), ByteArray(0), "a".repeat(64))
    assertEquals("a".repeat(64), session.instanceId)
    assertEquals("b".repeat(32), session.deviceId)
    assertEquals("D".repeat(43), session.sessionToken)
  }

  @Test fun pairRejectsInstanceIdMismatch() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody()).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "f".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsInvalidDeviceIdPattern() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody(deviceId = "ZZ".repeat(16))).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsInvalidDeviceTokenPattern() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody(deviceToken = "bad!")).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsPastSessionExpiresAt() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody(sessionExpiresAt = System.currentTimeMillis() - 1000)).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsDeviceExpiresBeforeSession() = runTest {
    val now = System.currentTimeMillis()
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody(deviceExpiresAt = now + 1000, sessionExpiresAt = now + 5000)).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsExtraKeys() = runTest {
    val body = pairBody().removeSuffix("}") + ""","extra":"x"}"""
    server.enqueue(MockResponse().setResponseCode(201).setBody(body).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairRejectsMissingKeys() = runTest {
    val body = """{"instanceId":"${"a".repeat(64)}","deviceId":"${"b".repeat(32)}","sessionToken":"${"D".repeat(43)}","csrfToken":"${"e".repeat(43)}","sessionExpiresAt":${System.currentTimeMillis() + 3600_000}}"""
    server.enqueue(MockResponse().setResponseCode(201).setBody(body).addHeader("Content-Type", "application/json"))
    val ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex)
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun pairMapsHttpErrorsToKinds() = runTest {
    server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
    var ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.PAIRING_EXPIRED, ex!!.kind)
    server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
    ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.PAIRING_EXPIRED, ex!!.kind)
    server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
    ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.DEVICE_LIMIT, ex!!.kind)
    server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
    ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.RATE_LIMITED, ex!!.kind)
    server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
    ex = runCatching { client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.SERVER_UNAVAILABLE, ex!!.kind)
  }

  @Test fun renewMapsHttpErrorsToKinds() = runTest {
    server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
    val ex = runCatching { client().renew(origin(), "C".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.PAIRING_EXPIRED, ex!!.kind)
  }

  @Test fun pairMapsTlsException() = runTest {
    val tlsFactory: (ByteArray, String) -> OkHttpClient = { _, _ ->
      OkHttpClient.Builder().addInterceptor { throw SSLException("tls fail") }.build()
    }
    val tlsClient = NativeAuthClient(bootstrapClient(), tlsFactory)
    val ex = runCatching { tlsClient.pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.TLS, ex!!.kind)
  }

  @Test fun pairMapsTimeoutException() = runTest {
    val factory: (ByteArray, String) -> OkHttpClient = { _, _ ->
      OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("timeout") }.build()
    }
    val c = NativeAuthClient(bootstrapClient(), factory)
    val ex = runCatching { c.pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.TIMEOUT, ex!!.kind)
  }

  @Test fun pairMapsNetworkException() = runTest {
    val factory: (ByteArray, String) -> OkHttpClient = { _, _ ->
      OkHttpClient.Builder().addInterceptor { throw ConnectException("unreachable") }.build()
    }
    val c = NativeAuthClient(bootstrapClient(), factory)
    val ex = runCatching { c.pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.NETWORK, ex!!.kind)
  }

  @Test fun parseNativeSessionResponseValidates() {
    val json = pairBody()
    val session = parseNativeSessionResponse(json, "a".repeat(64), PAIR_KEYS)
    assertEquals("a".repeat(64), session.instanceId)
  }

  @Test fun parseNativeSessionResponseRejectsMismatch() {
    val json = pairBody()
    val ex = runCatching { parseNativeSessionResponse(json, "f".repeat(64), PAIR_KEYS) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, ex!!.kind)
  }

  @Test fun trustAllClientCreates() {
    val c = trustAllClient()
    assertNotNull(c)
  }

  @Test fun fetchPairingCaMapsTlsException() = runTest {
    val badBootstrap = OkHttpClient.Builder().addInterceptor { throw SSLException("tls") }.build()
    val c = NativeAuthClient(badBootstrap, sessionFactory())
    val ex = runCatching { c.fetchPairingCa(origin()) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.TLS, ex!!.kind)
  }

  @Test fun fetchPairingCaMapsTimeoutException() = runTest {
    val badBootstrap = OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("t") }.build()
    val c = NativeAuthClient(badBootstrap, sessionFactory())
    val ex = runCatching { c.fetchPairingCa(origin()) }.exceptionOrNull() as? NativeAuthException
    assertNotNull(ex); assertEquals(NativeAuthFailureKind.TIMEOUT, ex!!.kind)
  }

  @Test fun postForSessionSendsJsonPayload() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(pairBody()).addHeader("Content-Type", "application/json"))
    client().pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64), label = "my-label")
    val req = server.takeRequest()
    assertTrue(req.path!!.contains("/amadeus/auth/native-pair"))
    val body = req.body.readUtf8()
    assertTrue(body.contains("\"token\":\"${"T".repeat(43)}\""))
    assertTrue(body.contains("\"label\":\"my-label\""))
  }

  @Test fun renewSendsDeviceTokenPayload() = runTest {
    server.enqueue(MockResponse().setResponseCode(201).setBody(renewBody()).addHeader("Content-Type", "application/json"))
    client().renew(origin(), "C".repeat(43), ByteArray(0), "a".repeat(64))
    val req = server.takeRequest()
    assertTrue(req.path!!.contains("/amadeus/auth/native-renew"))
    assertTrue(req.body.readUtf8().contains("\"deviceToken\":\"${"C".repeat(43)}\""))
  }
}
