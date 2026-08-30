package com.amadeus.whale.settings

import com.amadeus.whale.AmadeusPrefs
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.theatre.AmbientSoundController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
  private val prefs: AmadeusPrefs,
  private val apiProvider: (String) -> AmadeusApi,
) {
  private val _url = MutableStateFlow(prefs.baseUrl ?: "")
  val url: StateFlow<String> = _url
  private val _status = MutableStateFlow("")
  val status: StateFlow<String> = _status

  suspend fun testAndSave(url: String): Boolean {
    val api = apiProvider(url)
    return runCatching {
      if (!api.health()) return false
      prefs.baseUrl = url
      _status.value = "已连接"
      true
    }.getOrElse {
      _status.value = "连接失败: ${it.message}"
      false
    }
  }

  fun disconnect() { prefs.clear(); _status.value = "已断开" }

  var soundEnabled: Boolean
    get() = prefs.soundEnabled
    set(v) { prefs.soundEnabled = v; AmbientSoundController.enabled = v }
  var showToolProgress: Boolean
    get() = prefs.showToolProgress
    set(v) { prefs.showToolProgress = v }
}