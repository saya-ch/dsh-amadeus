package com.amadeus.whale.data.store

import android.content.SharedPreferences

/** 轻量键值存储（供凭据/内部状态用，非用户偏好——用户偏好走 DataStore DevicePrefsStore）。 */
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
