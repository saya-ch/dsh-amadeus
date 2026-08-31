package com.amadeus.whale.settings

import com.amadeus.whale.AmadeusPrefs
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.pairing.AuthResult
import com.amadeus.whale.pairing.GatewayOrigin
import com.amadeus.whale.pairing.NativeAuthFailureKind
import com.amadeus.whale.pairing.PairingService
import com.amadeus.whale.theatre.AmbientSoundController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

private object NoopPairingService : PairingService {
  override suspend fun pair(keyInput: String): AuthResult =
    AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, "未初始化配对服务")
  override suspend fun restore(origin: GatewayOrigin): AuthResult =
    AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, "未初始化配对服务")
}

class SettingsViewModel(
  private val prefs: AmadeusPrefs,
  private val apiProvider: (String) -> AmadeusApi,
  private val pairing: PairingService,
) {
  constructor(
    prefs: AmadeusPrefs,
    apiProvider: (String) -> AmadeusApi,
  ) : this(prefs, apiProvider, NoopPairingService)
  private val _url = MutableStateFlow(prefs.baseUrl ?: "")
  val url: StateFlow<String> = _url
  private val _status = MutableStateFlow("")
  val status: StateFlow<String> = _status

  private val _pairingStatus = MutableStateFlow("未配对")
  val pairingStatus: StateFlow<String> = _pairingStatus

  private var _lastSessionClient: OkHttpClient? = null
  val lastSessionClient: OkHttpClient? get() = _lastSessionClient

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

  fun disconnect() {
    prefs.clear()
    _status.value = "已断开"
    _pairingStatus.value = "未配对"
    _lastSessionClient = null
  }

  suspend fun pairWithKey(keyInput: String): Boolean {
    val result = pairing.pair(keyInput)
    return when (result) {
      is AuthResult.Success -> {
        prefs.baseUrl = result.origin.serialized
        _pairingStatus.value = "已配对 · ${result.deviceId.takeLast(8)}"
        _status.value = "已连接"
        _lastSessionClient = result.client
        true
      }
      is AuthResult.Failure -> {
        _pairingStatus.value = "配对失败: ${result.message}"
        _status.value = "配对失败: ${result.message}"
        false
      }
    }
  }

  fun markPaired(deviceId: String) {
    _pairingStatus.value = "已配对 · ${deviceId.takeLast(8)}"
  }

  suspend fun registerScanResult(text: String): Boolean = pairWithKey(text)

  var soundEnabled: Boolean
    get() = prefs.soundEnabled
    set(v) {
      prefs.soundEnabled = v
      AmbientSoundController.enabled = v
      // 关闭开关时同步停掉正在循环/播放的音频，而不只是拦截后续 play()
      if (!v) AmbientSoundController.stopAll()
    }
  var showToolProgress: Boolean
    get() = prefs.showToolProgress
    set(v) { prefs.showToolProgress = v }
}
