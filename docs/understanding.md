# Amadeus: Whale — 设计意图与当前实施状态

## 文档范围

本页记录 2026-08-30 的早期产品方案及其实施限制，不是已完成的功能清单。Amadeus 的目标是独立 Galgame App 与配套 DSH 插件；当前可用范围以 [项目说明](../README.md) 和源码为准。此次修改只处理与 dsh-mobile 共存及启动，不证明真实 DSH 会话、Mode、事件或手机端闭环已经可用。

## 目录

- [当前实施状态](#当前实施状态)
- [产品设计目标](#产品设计目标)
- [连接与数据流](#连接与数据流)
- [后续验收要求](#后续验收要求)
- [早期方案记录](#早期方案记录)

## 当前实施状态

`dsh-amadeus` 的 npm 包、App 和 [GitHub 仓库](https://github.com/saya-ch/dsh-amadeus) 保持独立。Host 依赖 `dsh-mobile ^0.3.3`（`>=0.3.3 <0.4.0`）提供的 `mobileAccess` 服务，仅注册 `amadeus` 扩展；网关监听、TLS、设备存储、配对、远程通道和 `mobile` 管理命令都由 dsh-mobile 拥有。

扩展业务地址为 `/mobile-access/extensions/amadeus/routes/...`。`/status` 用于说明就绪情况，`/tag/ensure` 用于标签调试；会话、报告和选择回复接口在没有真实后端时返回 `503`，不以内存 Map 模拟成功。路径与方法由 [路由源码](../src/amadeus-extension.ts) 定义。

人格提示词、权限字段和标签函数不等于已接入 DSH。`amadeus` Mode 注册、提示词注入、持久会话、工具交互和事件订阅均未完成真实链路验证。[Compose 与网络调用草稿](../app/amadeus/README.md) 未接入经过验证的完整 Android 工程，本轮未构建或验证 APK，也未验证 SDK、认证模块或 WebSocket 接线。本地被 Git 忽略的 Android 工程不构成干净检出可构建的证据，详见 [构建限制](../app/README.md#界面与构建限制)。

## 产品设计目标

这些目标需要独立实现与验收，不能从插件启动成功推导出来。

### 人格与会话

鲸鱼娘助手的体验目标是“记得你、陪着你、温柔可靠”，通过选档页进入 DSH 持久会话。设计使用 `amadeus` 标识区分会话，并在 DSH 支持的组合点接入人格提示词。高权限及自动审批字段目前仅属于配置草稿；真实权限必须由 DSH 的权限与审批机制落实，不得以提示词或手机演出替代。

### 标签与剧场

公开台词按短句分页，每句附带 `[[AMW:{...}]]` JSON 标签。目标是用 `mood/sprite` 选择立绘，用 `voice/sfx/bgm` 选择音效，并用独立窗口展示报告、预览或用户选项。思考与工具进度的展示应来自明确的公开事件，不把模型内部推理当作台词。

[标签函数](../src/amadeus-tags.ts) 可以独立处理文本，但函数存在不代表模型输出已被自动补全。Android 的标签解析与渲染仍需接入实际事件、资源和音频播放。

### 独立 Android App

目标界面是原生全屏立绘与底部对话框，不是将 dsh-mobile App 改名发布。App 可以参考 dsh-mobile 的连接实现，但需要自己的认证客户端、配对记录、证书信任和会话 Cookie。不同 App 不会自动共享这些凭据。

## 连接与数据流

电脑连接层复用 dsh-mobile 服务，不复制第二个 Gateway。

```text
Amadeus App（独立配对与凭据，待实现）
  → dsh-mobile 网关（TLS / Cookie / Origin / CSRF）
  → /mobile-access/extensions/amadeus/routes/...
  → Amadeus 扩展路由
  → 真实 DSH 会话与事件适配（待实现验证）
```

写请求必须满足 dsh-mobile 的同源请求和 CSRF 要求；普通 `OkHttpClient` 并不自动获得登录状态。详细客户端要求见 [App 说明](../app/README.md)。扩展没有 `/session/:id/stream` 或 `/message` 实现，不能把历史示意地址当作可调用接口。

## 后续验收要求

完整 Galgame MVP 需要分别取得以下证据，不能互相替代。

1. 同一 profile 加载 dsh-mobile 与 dsh-amadeus，确认只有 dsh-mobile 提供网关与管理服务。
2. Amadeus App 独立完成 TLS 信任与配对，并验证会话续期、Cookie、Origin、CSRF 和撤销行为。
3. 接入 DSH 的真实会话、人格配置和公开事件，验证重启后的会话持久性与权限行为。
4. 从手机创建会话，接收实际回复及工具状态，完成标签分页、立绘与音效展示，并在设备上验收。

## 早期方案记录

初始方案提出 Fork `dsh-mobile/apps/mobile` 和网关文件、开启独立端口，并假定存在 `ctx.modes.register`、`before_send_to_user` 及自定义会话 WebSocket。这些是早期设计假设，不是已验证的 DSH API 或当前实施方式。当前修复采用共享电脑连接层、独立 Amadeus 扩展的方案；原方案中的美术、Live2D 和演出设计仍是产品方向，不构成完成声明。
