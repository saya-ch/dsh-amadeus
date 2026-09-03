package com.amadeus.whale.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.amadeus.whale.theme.AmadeusThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.amadeusDataStore: DataStore<Preferences> by preferencesDataStore(name = "amadeus_device")

/** 设备级键值偏好（架构 3.4）：demo_seen / 网关 / 主题 / BGM / 背景 / 文字速度 / 工具进度 / 触觉。 */
data class DevicePrefs(
  val demoSeen: Boolean = false,
  val gatewayUrl: String? = null,
  val themeId: AmadeusThemeId = AmadeusThemeId.WARM_HEALING,
  val bgmTrack: String = "rain",
  val bgmEnabled: Boolean = true,
  val bgmVolume: Int = 70,            // 0..100
  val background: String = "bg-claude-writing-study",
  val textSpeed: Int = 1,             // 0=慢 1=中 2=快
  val toolProgress: Boolean = true,
  val hapticsEnabled: Boolean = true,
  val lastSessionId: String? = null,
)

class DevicePrefsStore(private val context: Context) {
  private object Keys {
    val demoSeen = booleanPreferencesKey("demo_seen")
    val gatewayUrl = stringPreferencesKey("gateway_url")
    val themeId = stringPreferencesKey("theme_id")
    val bgmTrack = stringPreferencesKey("bgm_track")
    val bgmEnabled = booleanPreferencesKey("bgm_enabled")
    val bgmVolume = intPreferencesKey("bgm_volume")
    val background = stringPreferencesKey("background")
    val textSpeed = intPreferencesKey("text_speed")
    val toolProgress = booleanPreferencesKey("tool_progress")
    val hapticsEnabled = booleanPreferencesKey("haptics_enabled")
    val lastSessionId = stringPreferencesKey("last_session_id")
  }

  val flow: Flow<DevicePrefs> = context.amadeusDataStore.data.map { p ->
    DevicePrefs(
      demoSeen = p[Keys.demoSeen] ?: false,
      gatewayUrl = p[Keys.gatewayUrl],
      themeId = runCatching { AmadeusThemeId.valueOf(p[Keys.themeId] ?: "") }.getOrDefault(AmadeusThemeId.WARM_HEALING),
      bgmTrack = p[Keys.bgmTrack] ?: "rain",
      bgmEnabled = p[Keys.bgmEnabled] ?: true,
      bgmVolume = p[Keys.bgmVolume] ?: 70,
      background = p[Keys.background] ?: "bg-claude-writing-study",
      textSpeed = p[Keys.textSpeed] ?: 1,
      toolProgress = p[Keys.toolProgress] ?: true,
      hapticsEnabled = p[Keys.hapticsEnabled] ?: true,
      lastSessionId = p[Keys.lastSessionId],
    )
  }

  suspend fun snapshot(): DevicePrefs = flow.first()

  suspend fun setDemoSeen(v: Boolean) = edit { it[Keys.demoSeen] = v }
  suspend fun setGatewayUrl(v: String?) = edit { if (v == null) it.remove(Keys.gatewayUrl) else it[Keys.gatewayUrl] = v }
  suspend fun setThemeId(v: AmadeusThemeId) = edit { it[Keys.themeId] = v.name }
  suspend fun setBgmTrack(v: String) = edit { it[Keys.bgmTrack] = v }
  suspend fun setBgmEnabled(v: Boolean) = edit { it[Keys.bgmEnabled] = v }
  suspend fun setBgmVolume(v: Int) = edit { it[Keys.bgmVolume] = v.coerceIn(0, 100) }
  suspend fun setBackground(v: String) = edit { it[Keys.background] = v }
  suspend fun setTextSpeed(v: Int) = edit { it[Keys.textSpeed] = v.coerceIn(0, 2) }
  suspend fun setToolProgress(v: Boolean) = edit { it[Keys.toolProgress] = v }
  suspend fun setHapticsEnabled(v: Boolean) = edit { it[Keys.hapticsEnabled] = v }
  suspend fun setLastSessionId(v: String) = edit { it[Keys.lastSessionId] = v }

  private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    context.amadeusDataStore.edit { block(it) }
  }
}
