package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.data.store.DevicePrefsStore
import com.amadeus.whale.domain.AppLaunchDecider
import com.amadeus.whale.root.AppRoot

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefsStore = DevicePrefsStore(applicationContext)
    // 骨架阶段：恢复会话暂用"无网关则 null"的最小实现（配对层后续迁移接入）
    val decider = AppLaunchDecider(
      prefs = { prefsStore.snapshot() },
      restoreLastSession = { gateway -> null }, // TODO: 接入 AuthService 恢复最后活动会话
    )
    setContent { AppRoot(prefsStore = prefsStore, launchDecider = decider) }
  }
}
