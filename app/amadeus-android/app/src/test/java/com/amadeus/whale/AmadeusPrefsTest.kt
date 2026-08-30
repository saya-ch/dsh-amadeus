package com.amadeus.whale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmadeusPrefsTest {
  private class FakeStore : PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun getString(k: String) = m[k]
    override fun putString(k: String, v: String) { m[k] = v }
    override fun getBoolean(k: String, def: Boolean) = m[k]?.toBoolean() ?: def
    override fun putBoolean(k: String, v: Boolean) { m[k] = v.toString() }
    override fun remove(k: String) { m.remove(k) }
  }
  @Test fun roundtripBaseUrl() {
    val p = AmadeusPrefs(FakeStore())
    assertNull(p.baseUrl)
    p.baseUrl = "https://192.168.1.5:3444"
    assertEquals("https://192.168.1.5:3444", p.baseUrl)
  }
  @Test fun clearRemovesBaseUrl() {
    val p = AmadeusPrefs(FakeStore())
    p.baseUrl = "https://x:3444"
    p.clear()
    assertNull(p.baseUrl)
  }
}