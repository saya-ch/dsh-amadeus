package com.amadeus.whale.root

/** 页面级路由（架构 3.1：自建轻量路由）。覆盖层不走这里（OverlayHost 管）。 */
sealed class Screen {
  /** 标题画面（logo 淡入，兼作启动决策加载态，架构 3.13）。 */
  data object Title : Screen()

  /** demo 剧场（设备首次进 App 展示，产品 1.10）。 */
  data object Demo : Screen()

  /** 连接剧场：鲸鱼娘等你接入（产品 1.12 / 架构 3.16）。 */
  data class Connection(val firstPairing: Boolean) : Screen()

  /** 读档页：按工作区分组（产品 1.10 / 架构 3.15）。 */
  data object SaveSlot : Screen()

  /** 剧场（真实模式）：主场景。 */
  data class Theatre(val sessionId: String) : Screen()
}
