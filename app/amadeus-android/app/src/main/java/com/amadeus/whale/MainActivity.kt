package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.data.HttpAuthService
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.data.store.SharedPrefsStore
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.pairing.AmadeusAuthClient
import com.amadeus.whale.pairing.KeystoreDeviceCredentialStore
import com.amadeus.whale.pairing.NativeAuthClient
import com.amadeus.whale.pairing.PinnedTls
import com.amadeus.whale.root.AppRoot
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefsStore = DevicePrefsStore(applicationContext)
    val sharedPrefs = SharedPrefsStore(
      applicationContext.getSharedPreferences("amw_credentials", android.content.Context.MODE_PRIVATE),
    )

    // 配对用的 bootstrap client（不 pin，用于 ca.cer 拉取）
    val bootstrapClient = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()

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
        // 恢复已保存凭据（架构 3.7）
        authService.restore(gateway)?.let { client ->
          authService.currentSession()?.let { it.origin.serialized }
        }
      },
    )
    setContent { AppRoot(prefsStore = prefsStore, launchDecider = decider, authService = authService) }
  }
}
