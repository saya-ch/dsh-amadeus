package com.amadeus.whale.settings

import com.amadeus.whale.AmadeusPrefs
import com.amadeus.whale.PrefsStore
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.theatre.AmbientSoundController
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest {
  private lateinit var server: MockWebServer
  private lateinit var vm: SettingsViewModel
  private lateinit var store: PrefsStore
  private val client = OkHttpClient()

  class MemStore : PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun getString(k: String) = m[k]
    override fun putString(k: String, v: String) { m[k] = v }
    override fun getBoolean(k: String, def: Boolean) = m[k]?.toBoolean() ?: def
    override fun putBoolean(k: String, v: Boolean) { m[k] = v.toString() }
    override fun remove(k: String) { m.remove(k) }
  }

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    store = MemStore()
    val prefs = AmadeusPrefs(store)
    vm = SettingsViewModel(prefs) { AmadeusApi(it.trimEnd('/'), client) }
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun testAndSavePersistsOnHealthOk() = runTest {
    server.enqueue(MockResponse().setBody("""{"capabilities":{"sessions":true}}""").addHeader("Content-Type", "application/json"))
    val ok = vm.testAndSave(server.url("/").toString().trimEnd('/'))
    assertTrue(ok)
    assertEquals(server.url("/").toString().trimEnd('/'), store.getString("gateway_url"))
  }

  @Test fun disconnectClearsUrl() = runTest {
    store.putString("gateway_url", "https://x:3444")
    vm.disconnect()
    assertNull(store.getString("gateway_url"))
  }

  @Test fun soundEnabledSetterTogglesControllerAndPersists() {
    AmbientSoundController.enabled = true
    store.putBoolean("sound_enabled", true)
    vm.soundEnabled = false
    assertFalse(AmbientSoundController.enabled)
    assertEquals(false, store.getBoolean("sound_enabled", true))
    vm.soundEnabled = true
    assertTrue(AmbientSoundController.enabled)
    assertEquals(true, store.getBoolean("sound_enabled", false))
  }

  @Test fun pairWithKeyParsesAndStores() = runTest {
    val prefs = AmadeusPrefs(store)
    val fakePairing = FakePairingService(success = true)
    val vm2 = SettingsViewModel(prefs, { AmadeusApi(it.trimEnd('/'), client) }, fakePairing)
    val input = "https://192.168.1.20:3444/mobile-access/pair#instance=${"a".repeat(64)}&token=${"A".repeat(43)}"
    val ok = vm2.pairWithKey(input)
    assertTrue(ok)
    assertEquals("https://192.168.1.20:3444", store.getString("gateway_url"))
    assertTrue(vm2.pairingStatus.value.contains("已配对"))
    assertEquals("已连接", vm2.status.value)
  }

  @Test fun pairWithBareKeyRejectsWithHint() = runTest {
    val prefs = AmadeusPrefs(store)
    val bareKey = "dsh1.${"a".repeat(64)}.${"A".repeat(43)}"
    // 使用真实 AmadeusPairingService 验证裸 appKey 被拦截并返回中文提示
    val fakeGateway = object : com.amadeus.whale.pairing.NativeAuthGateway {
      override suspend fun fetchPairingCa(origin: com.amadeus.whale.pairing.GatewayOrigin): ByteArray = ByteArray(0)
      override suspend fun pair(origin: com.amadeus.whale.pairing.GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String?): com.amadeus.whale.pairing.NativeSession {
        throw AssertionError("should not be called for bare key")
      }
      override suspend fun renew(origin: com.amadeus.whale.pairing.GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): com.amadeus.whale.pairing.NativeSession {
        throw AssertionError("should not be called")
      }
    }
    val store2 = FakeCredentialStore()
    val auth = com.amadeus.whale.pairing.AmadeusAuthClient(fakeGateway, store2, OkHttpClient())
    val pairingService = com.amadeus.whale.pairing.AmadeusPairingService(auth)
    val vm2 = SettingsViewModel(prefs, { AmadeusApi(it.trimEnd('/'), client) }, pairingService)
    val ok = vm2.pairWithKey(bareKey)
    assertFalse(ok)
    assertTrue(vm2.pairingStatus.value.contains("配对 URL") || vm2.pairingStatus.value.contains("配对失败"))
    assertTrue(vm2.status.value.contains("配对失败"))
  }

  private class FakePairingService(private val success: Boolean) : com.amadeus.whale.pairing.PairingService {
    override suspend fun pair(keyInput: String): com.amadeus.whale.pairing.AuthResult {
      if (!success) return com.amadeus.whale.pairing.AuthResult.Failure(com.amadeus.whale.pairing.NativeAuthFailureKind.INVALID_RESPONSE, "无法解析配对密钥；请使用带网关地址的配对 URL 或扫码")
      val origin = com.amadeus.whale.pairing.GatewayOrigin.parse("https://192.168.1.20:3444")
      val c = OkHttpClient()
      return com.amadeus.whale.pairing.AuthResult.Success(origin, "a".repeat(64), "b".repeat(32), c, System.currentTimeMillis() + 3600_000)
    }
    override suspend fun restore(origin: com.amadeus.whale.pairing.GatewayOrigin): com.amadeus.whale.pairing.AuthResult {
      return com.amadeus.whale.pairing.AuthResult.Failure(com.amadeus.whale.pairing.NativeAuthFailureKind.INVALID_RESPONSE, "no")
    }
  }

  private class FakeCredentialStore : com.amadeus.whale.pairing.DeviceCredentialStore {
    private val map = mutableMapOf<String, com.amadeus.whale.pairing.DeviceCredential>()
    override fun save(origin: String, credential: com.amadeus.whale.pairing.DeviceCredential): Boolean { map[origin] = credential; return true }
    override fun load(origin: String): com.amadeus.whale.pairing.DeviceCredential? = map[origin]
    override fun clear(origin: String) { map.remove(origin) }
  }
}
