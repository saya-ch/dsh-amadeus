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
}