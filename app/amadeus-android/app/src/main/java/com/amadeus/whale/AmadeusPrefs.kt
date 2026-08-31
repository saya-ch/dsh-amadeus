package com.amadeus.whale

import android.content.Context
import android.content.SharedPreferences

interface PrefsStore {
  fun getString(key: String): String?
  fun putString(key: String, value: String)
  fun getBoolean(key: String, def: Boolean): Boolean
  fun putBoolean(key: String, value: Boolean)
  fun remove(key: String)
}

class SharedPrefsStore(private val sp: SharedPreferences) : PrefsStore {
  override fun getString(key: String) = sp.getString(key, null)
  override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
  override fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
  override fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
  override fun remove(key: String) { sp.edit().remove(key).apply() }
}

class AmadeusPrefs(store: PrefsStore) {
  companion object {
    private const val KEY_URL = "gateway_url"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_TOOL = "show_tool_progress"
    fun from(context: Context) = AmadeusPrefs(
      SharedPrefsStore(context.getSharedPreferences("amadeus", Context.MODE_PRIVATE))
    )
  }
  private val s = store
  var baseUrl: String?
    get() = s.getString(KEY_URL)
    set(value) { if (value == null) s.remove(KEY_URL) else s.putString(KEY_URL, value) }
  var soundEnabled: Boolean
    get() = s.getBoolean(KEY_SOUND, true)
    set(value) { s.putBoolean(KEY_SOUND, value) }
  var showToolProgress: Boolean
    get() = s.getBoolean(KEY_TOOL, false)
    set(value) { s.putBoolean(KEY_TOOL, value) }
  fun clear() { s.remove(KEY_URL) }
}