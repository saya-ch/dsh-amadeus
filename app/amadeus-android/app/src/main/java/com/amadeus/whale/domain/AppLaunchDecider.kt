package com.amadeus.whale.domain

import com.amadeus.whale.data.store.DevicePrefs

/** 启动决策结果（架构 3.13）：App 启动后进入哪个 Screen。 */
sealed class LaunchTarget {
  /** 首次进 App（demo 未看过）→ demo。 */
  data object FirstRunDemo : LaunchTarget()

  /** demo 已看过 + 无网关 → 连接剧场（日常重连）。 */
  data object ConnectDaily : LaunchTarget()

  /** demo 已看过 + 有网关 + 恢复成功 → 剧场（最后活动会话）。 */
  data class RealTheatre(val sessionId: String) : LaunchTarget()

  /** demo 已看过 + 有网关 + 恢复失败 → 连接剧场（重新配对）。 */
  data object ConnectAfterFailure : LaunchTarget()
}

/** 启动决策逻辑（可单测，不依赖 Android）。 */
class AppLaunchDecider(
  private val prefs: suspend () -> DevicePrefs,
  private val restoreLastSession: suspend (String) -> String?,
) {
  suspend fun decide(): LaunchTarget {
    val p = prefs()
    if (!p.demoSeen) return LaunchTarget.FirstRunDemo
    val gateway = p.gatewayUrl ?: return LaunchTarget.ConnectDaily
    val sessionId = restoreLastSession(gateway)
    return if (sessionId != null) LaunchTarget.RealTheatre(sessionId)
    else LaunchTarget.ConnectAfterFailure
  }
}
