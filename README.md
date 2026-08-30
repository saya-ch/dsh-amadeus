# Amadeus: Whale

Amadeus 的目标是在手机上用全屏立绘、对话框和 `[[AMW:{...}]]` 标签呈现鲸鱼娘助手。独立 npm 包 `dsh-amadeus` 和独立 App 在本仓库维护；电脑连接层由 `dsh-mobile` 提供。当前工作只覆盖插件共存与启动修复，真实 DSH 会话、Mode 和事件流尚未接通验证；`app/amadeus` 的 Kotlin 草稿未接入经过验证的完整 Android 工程，本轮未构建或验证 APK。

## 与 dsh-mobile 的关系

`dsh-amadeus` 是独立的插件与独立的 App，有自己的 GitHub 仓库与 npm 包，并不与 `dsh-mobile` 合并。

在实现上，`dsh-amadeus` 复用了 `dsh-mobile` 已经过验证的连接与安全能力（TLS、配对、设备会话、局域网与远程通道）。这样考虑主要是：

- **避免重复的网关**：`dsh-mobile` 已处理好证书固定、配对校验与远程通道等细节，`dsh-amadeus` 通过 `ctx.mobileAccess.registerExtension('amadeus')` 复用同一套认证与通道即可，无需再单独起一套端口与证书逻辑。
- **维护更集中**：连接层的安全修复只需在 `dsh-mobile` 更新一次，`dsh-amadeus` 随之受益，也减少了两套连接代码并行维护的成本。
- **体验更连贯**：在 `dsh-mobile` 上已完成的配对与设备信任，可以为 `Amadeus Whale` 的 Galgame 剧场复用，无需重复操作。

因此，**若希望在手机上以 Galgame 形式使用 `amadeus` Mode**，我们推荐在同一 DSH profile 中同时安装 `dsh-mobile@^0.3.3` 与 `dsh-amadeus`（`peerDependencies` 中 `dsh-mobile` 标记为可选，不会强制安装）。即便如此，`Amadeus App` 仍需作为独立客户端完成自己的配对并保存自己的凭据，不会直接读取 `dsh-mobile` App 的 Cookie 或设备令牌，详见 [App 说明](app/README.md)。

若暂时只需桌面端的 `amadeus` 人格、标签与报告能力，也可以单独安装 `dsh-amadeus` 使用，移动端的 Galgame 剧场可在后续需要时再与 `dsh-mobile` 配合启用。

## Host 扩展

Amadeus Host 向已有的 `ctx.mobileAccess.registerExtension` 注册扩展 `amadeus`。它不创建第二个 Gateway，不注册第二个 `mobileAccess` 服务，也不注册 `mobile` 管理命令。端口、证书、配对和通道设置由 dsh-mobile 管理。

业务请求使用前缀 `/mobile-access/extensions/amadeus/routes`，由 dsh-mobile 先完成认证与写请求保护，再分派给 Amadeus。路由实现由 [amadeus-extension.ts](src/amadeus-extension.ts) 管理。

| 路径与方法 | 当前用途 |
| --- | --- |
| `GET /status` | 查看扩展状态，不代表真实会话能力就绪 |
| `POST /tag/ensure` | 调试标签解析与补全，不会拦截模型输出 |
| `GET/POST /sessions` | 保留会话接口；未接入后端时返回 `503` |
| `GET/POST /reports`、`GET /reports/:id` | 保留报告接口；未接入后端时返回 `503` |
| `POST /choice` | 保留选项回复接口；未接入后端时返回 `503` |

## 人格与标签

[amadeus-mode.ts](src/amadeus-mode.ts) 保存人格提示词与 Mode 配置草稿，[amadeus-tags.ts](src/amadeus-tags.ts) 提供标签处理函数。扩展注册不等于 DSH Mode 注册；尚无已验证的模型请求注入、工具审批变更或会话事件接入。

## 当前限制

插件启动和标签函数检查不能替代完整 Galgame 会话验证。

- 真实 DSH 会话创建、持久化、`amadeus` Mode 和事件订阅未接通验证；不提供虚构的内存会话作为成功结果。
- 没有 `/session/:id/stream` 或 `/message` 扩展接口，也没有已接通的 Amadeus WebSocket 客户端。
- [Android 草稿](app/amadeus/README.md) 的工程集成、认证、渲染和 APK 尚未验证；本地被 Git 忽略的 Android 工程不代表干净检出可构建。
- 本仓库与 [dsh-mobile](https://github.com/saya-ch/dsh-mobile) 保持独立，依赖连接层不表示合并产品、npm 包或 GitHub 仓库。

## 相关文档

- [设计意图与当前实施状态](docs/understanding.md)
- [App 连接要求](app/README.md)
- [Kotlin 界面与调用草稿](app/amadeus/README.md)
- [独立 GitHub 仓库](https://github.com/saya-ch/dsh-amadeus)
