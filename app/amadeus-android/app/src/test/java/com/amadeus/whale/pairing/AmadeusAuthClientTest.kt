package com.amadeus.whale.pairing

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmadeusAuthClientTest {

  private fun origin(): GatewayOrigin = GatewayOrigin.parse("https://192.168.1.20:3444")

  private fun target(): PairingScanTarget = PairingScanTarget(origin(), "a".repeat(64), "T".repeat(43))

  @Test fun pairStoresCredentialAndReturnsClient() = runTest {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.pair(target())
    assertTrue(result is AuthResult.Success)
    val success = result as AuthResult.Success
    assertEquals("a".repeat(64), success.instanceId)
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
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now, ByteArray(0), origin().serialized))
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
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now, ByteArray(0), origin().serialized))
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
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now, ByteArray(0), origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    assertNotNull(auth.createSessionClient(origin()))
  }

  @Test fun clearRemovesCredential() {
    val fake = FakeNativeAuthGateway()
    val store = FakeCredentialStore()
    val now = System.currentTimeMillis() + 3600_000
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now, ByteArray(0), origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    auth.clear(origin())
    assertNull(store.load(origin().serialized))
  }
}

private class FakeNativeAuthGateway(
  var pairFails: Boolean = false,
  var renewFails: Boolean = false,
  var fetchFails: Boolean = false,
) : NativeAuthGateway {
  override suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray {
    if (fetchFails) throw NativeAuthException(NativeAuthFailureKind.NETWORK, "fetch failed")
    return ByteArray(0)
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
