# Amadeus Whale APP — Kotlin / Compose 草稿

本目录是原生 Galgame App 的设计与调用草稿，未接入经过验证的完整 Android 工程，也未验证真实 DSH 会话接入。本轮未构建或验证 APK。电脑侧由 `dsh-mobile ^0.3.3`（`>=0.3.3 <0.4.0`）提供连接层，Amadeus 的 npm 插件和 App 仍是独立产品。

## 架构

```text
app/amadeus/
  ui/theatre/  — 剧场、标签分页与渲染接口草稿
  ui/saveslot/ — 选档界面与 Repository 接口
  ui/window/   — 报告、预览与选项窗口草稿
  network/     — 扩展路由的 OkHttp 调用草稿
app/assets/    — 尚待接入 Android 资源系统的素材
```

## 连接要求

[AmadeusApi](network/AmadeusApi.kt) 的 `baseUrl` 指向 dsh-mobile 网关，不是独立 Amadeus 端口。会话请求使用 `/mobile-access/extensions/amadeus/routes/sessions`，非成功 HTTP 响应作为错误返回；后端未接入时的 `503` 不是空列表。

调用方必须实现 [TLS、独立配对、Cookie、Origin 与 CSRF](../README.md#网络调用)。本草稿与 `NetworkModule`、DSH SDK 或 WebSocket 的集成尚未验证；`TheatreViewModel.onMessage` 只是接收调用方提供的字符串。Amadeus 不自动获得另一个 App 的配对凭据。

## 渲染与构建限制

[SpriteRenderer.kt](ui/theatre/SpriteRenderer.kt) 定义静态立绘和 Live2D 接口，但两种 `Render` 实现均是占位。没有已集成的 Coil 动画或 Live2D Cubism SDK，不能通过替换一个实现类就得到可运行剧场。

本草稿与 Gradle 工程、应用入口、认证客户端、Repository 和资源打包的集成尚未验证。Compose、OkHttp、协程、图像与音频依赖需要通过实际构建验证，不能把示例依赖列表当作已安装版本。本地被 Git 忽略的 `app/android/` 工程不代表干净检出可构建，详见 [构建限制](../README.md#界面与构建限制)。

## 标签驱动

[TheatreViewModel](ui/theatre/TheatreViewModel.kt) 草稿从字符串中的 `[[AMW:{...}]]` 标签读取 `mood/sprite` 并形成分页状态。真实事件订阅、完整标签校验、音效、报告及选项交互尚未完成端到端验证。此次 Host 共存与启动修复不包含完整 Galgame 实现。
