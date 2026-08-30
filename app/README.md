# Amadeus Whale APP

本目录保存独立 Amadeus Android App 的界面与网络调用草稿及素材，工程集成和设备端效果尚未验证。电脑连接层使用 `dsh-mobile ^0.3.3`（`>=0.3.3 <0.4.0`）；App 与 dsh-mobile App 保持独立。

## 网络调用

[AmadeusApi.kt](amadeus/network/AmadeusApi.kt) 使用 dsh-mobile 网关地址作为 `baseUrl`，业务前缀为 `/mobile-access/extensions/amadeus/routes`。选档调用草稿请求 `GET /sessions?mode=amadeus` 与 `POST /sessions`；后端未接入时返回 `503`，不能将其显示为空存档或创建成功。

构造器接收的 `OkHttpClient` 必须由认证模块配置；当前草稿尚未验证以下接线：

- TLS 证书验证与证书信任记录；不得使用接受所有证书的实现。
- Amadeus 自己的配对、设备凭据、会话续期和 Cookie 存储。
- 写请求的匹配 `Origin`、`Sec-Fetch-Site: same-origin` 与 `x-dsh-mobile-csrf`，后者来自当前会话的 `dsh_ma_csrf` Cookie。
- 配对失效、凭据撤销及网络失败的可见错误处理。

当前草稿与 `NetworkModule`、DSH SDK 或 WebSocket 的集成尚未验证。dsh-mobile App 的配对记录、Cookie 与证书信任不会自动共享给 Amadeus，也不能假定使用同一台手机就已认证。

## 界面与构建限制

[Kotlin 草稿](amadeus/README.md) 包含选档页、剧场、标签分页和窗口组件，但未接入经过验证的完整 Android 工程；静态立绘与 Live2D 渲染实现仍是占位。本轮未构建或验证 APK，也未验证真实 DSH 会话事件与设备端闭环。

本地 `app/android/` 中存在 `settings.gradle.kts`，但该目录被 [.gitignore](../.gitignore) 忽略。本地工程存在不代表干净检出可构建，也不证明 `app/amadeus` 草稿已接入其构建与应用入口。

## 资源

- [立绘素材](assets/sprites/README.md)
- [背景素材目录](assets/backgrounds/)

素材存在不代表已被 Android 资源系统打包或被渲染器加载。整体范围与后端限制见 [项目说明](../README.md)。
