package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.data.HttpSessionRepository
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.root.AppRoot
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefsStore = DevicePrefsStore(applicationContext)
    val bootstrapClient = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
    val decider = AppLaunchDecider(
      prefs = { prefsStore.snapshot() },
      restoreLastSession = { gateway ->
        // TODO: 接入 AuthService 恢复（架构 3.7），骨架阶段先恢复失败
        null
      },
    )
    val sessionRepository = HttpSessionRepository("", bootstrapClient) // baseUrl 由网关状态决定
    setContent { AppRoot(prefsStore = prefsStore, launchDecider = decider, sessionRepository = sessionRepository) }
  }
}
