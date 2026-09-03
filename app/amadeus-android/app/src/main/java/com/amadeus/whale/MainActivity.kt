package com.amadeus.whale

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.amadeus.whale.data.HttpAuthService
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.data.store.SharedPrefsStore
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.pairing.AmadeusAuthClient
import com.amadeus.whale.pairing.KeystoreDeviceCredentialStore
import com.amadeus.whale.pairing.NativeAuthClient
import com.amadeus.whale.pairing.PinnedTls
import com.amadeus.whale.pairing.trustAllClient
import com.amadeus.whale.root.AppRoot

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 沉浸式：收起状态栏+导航栏，剧场全屏（产品 1.x：galgame 沉浸体验）
    enableEdgeToEdge()
    window.setFlags(
      WindowManager.LayoutParams.FLAG_FULLSCREEN,
      WindowManager.LayoutParams.FLAG_FULLSCREEN,
    )
    window.insetsController?.apply {
      hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
      systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    val prefsStore = DevicePrefsStore(applicationContext)
    val sharedPrefs = SharedPrefsStore(
      applicationContext.getSharedPreferences("amw_credentials", android.content.Context.MODE_PRIVATE),
    )

    // 配对用的 bootstrap client（trust-all：拉 ca.cer 时还没有 CA 可信任，
    // 配对成功后换成 pin 后的 session client）
    val bootstrapClient = trustAllClient()

    // session client 工厂：配对成功后用 pin 后的 client 建仓库
    val credentialStore = KeystoreDeviceCredentialStore(sharedPrefs)
    val nativeAuth = NativeAuthClient(
      bootstrapClient = bootstrapClient,
      sessionClientFactory = { caDer, instanceId ->
        bootstrapClient.newBuilder()
          .sslSocketFactory(
            PinnedTls.socketFactory(caDer, instanceId),
            PinnedTls.trustManager(caDer, instanceId),
          )
          .build()
      },
    )
    val authClient = AmadeusAuthClient(nativeAuth, credentialStore, bootstrapClient)
    val authService = HttpAuthService(authClient)

    val decider = AppLaunchDecider(
      prefs = { prefsStore.snapshot() },
      restoreLastSession = { gateway ->
        // 恢复已保存凭据（架构 3.7）；成功返回上次会话 id（有则回 Connected 续聊，无则占位懒查）。
        // 5s 超时兜底——旧网关连不上时快速降级到连接页，绝不卡黑屏。
        try {
          kotlinx.coroutines.withTimeout(5_000) {
            if (authService.restore(gateway) != null) {
              prefsStore.snapshot().lastSessionId ?: "restored"
            } else null
          }
        } catch (error: Exception) {
          null
        }
      },
    )
    setContent { AppRoot(prefsStore = prefsStore, launchDecider = decider, authService = authService) }
  }
}
