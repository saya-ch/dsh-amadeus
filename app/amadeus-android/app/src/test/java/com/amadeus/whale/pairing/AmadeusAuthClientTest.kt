package com.amadeus.whale.pairing

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmadeusAuthClientTest {

  private fun origin(): GatewayOrigin = GatewayOrigin.parse("https://192.168.1.20:3444")

  private fun target(): PairingScanTarget = PairingScanTarget(origin(), TestCa.fingerprint, "T".repeat(43))

  @Test fun pairStoresCredentialAndReturnsClient() = runTest {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.pair(target())
    assertTrue(result is AuthResult.Success)
    val success = result as AuthResult.Success
    assertEquals(TestCa.fingerprint, success.instanceId)
    assertNotNull(store.load(origin().serialized))
  }

  @Test fun pairFailureReturnsFailure() = runTest {
    val fake = FakeNativeAuthGateway(pairFails = true)
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.pair(target())
    assertTrue(result is AuthResult.Failure)
  }

  @Test fun restoreRenewsExistingCredential() = runTest {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val now = System.currentTimeMillis() + 3600_000
    store.save(origin().serialized, DeviceCredential(TestCa.fingerprint, "C".repeat(43), now, TestCa.der, origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.restore(origin())
    assertTrue(result is AuthResult.Success)
  }

  @Test fun restoreFailsWhenNoCredential() = runTest {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.restore(origin())
    assertTrue(result is AuthResult.Failure)
    val failure = result as AuthResult.Failure
    assertEquals(NativeAuthFailureKind.INVALID_RESPONSE, failure.kind)
  }

  @Test fun restoreClearsOnFailure() = runTest {
    val fake = FakeNativeAuthGateway(renewFails = true)
    val store = FakeCredentialStore()
    val now = System.currentTimeMillis() + 3600_000
    store.save(origin().serialized, DeviceCredential(TestCa.fingerprint, "C".repeat(43), now, TestCa.der, origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.restore(origin())
    assertTrue(result is AuthResult.Failure)
    assertNull(store.load(origin().serialized))
  }

  @Test fun createSessionClientReturnsNullWhenNoCredential() {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    assertNull(auth.createSessionClient(origin()))
  }

  @Test fun createSessionClientReturnsNonNullWhenHasCredential() {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val now = System.currentTimeMillis() + 3600_000
    store.save(origin().serialized, DeviceCredential(TestCa.fingerprint, "C".repeat(43), now, TestCa.der, origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    assertNotNull(auth.createSessionClient(origin()))
  }

  @Test fun clearRemovesCredential() {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val now = System.currentTimeMillis() + 3600_000
    store.save(origin().serialized, DeviceCredential(TestCa.fingerprint, "C".repeat(43), now, TestCa.der, origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    auth.clear(origin())
    assertNull(store.load(origin().serialized))
  }
}

private object TestCa {
  private val pair: Pair<X509Certificate, String> by lazy { makeSelfSignedCa() }

  val cert: X509Certificate get() = pair.first
  val fingerprint: String get() = pair.second
  val der: ByteArray get() = cert.encoded

  private fun ensureBc() {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(BouncyCastleProvider())
    }
  }

  private fun makeSelfSignedCa(): Pair<X509Certificate, String> {
    ensureBc()
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(256, SecureRandom())
    val kp: KeyPair = kpg.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = JcaX509v3CertificateBuilder(
      X500Principal("CN=amadeus-test"),
      BigInteger.ONE,
      Date(now - 1000),
      Date(now + 365L * 24 * 3600 * 1000),
      X500Principal("CN=amadeus-test"),
      kp.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(kp.private)
    val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
    val fingerprint = PinnedTls.sha256Fingerprint(cert)
    return cert to fingerprint
  }
}

private class FakeNativeAuthGateway(
  var pairFails: Boolean = false,
  var renewFails: Boolean = false,
  var fetchFails: Boolean = false,
) : NativeAuthGateway {
  override suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray {
    if (fetchFails) throw NativeAuthException(NativeAuthFailureKind.NETWORK, "fetch failed")
    return TestCa.der
  }

  override suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String?): NativeSession {
    if (pairFails) throw NativeAuthException(NativeAuthFailureKind.PAIRING_EXPIRED, "pair expired")
    val future = System.currentTimeMillis() + 3600_000
    return NativeSession(instanceId, "b".repeat(32), "C".repeat(43), future, "D".repeat(43), "e".repeat(43), future)
  }

  override suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession {
    if (renewFails) throw NativeAuthException(NativeAuthFailureKind.PAIRING_EXPIRED, "expired")
    val future = System.currentTimeMillis() + 3600_000
    return NativeSession(instanceId, "b".repeat(32), null, null, "D".repeat(43), "e".repeat(43), future)
  }
}

private class FakeCredentialStore : DeviceCredentialStore {
  private val map = mutableMapOf<String, DeviceCredential>()
  override fun save(origin: String, credential: DeviceCredential): Boolean { map[origin] = credential; return true }
  override fun load(origin: String): DeviceCredential? = map[origin]
  override fun clear(origin: String) { map.remove(origin) }
}
