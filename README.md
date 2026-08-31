# Amadeus: Whale

DSH 的自定义高权限 Mode，配套独立移动端 App「Amadeus Whale」：用户在手机上以 Galgame 形式与鲸鱼娘人格的 Agent 互动，Agent 在电脑幕后正常执行工作，长文本（报告/结果）通过可点开的窗口查看。

叙事线：**demo 讲用户与鲸鱼娘的相识（海边月夜初遇）；之后每次新建会话都是用户与鲸鱼娘在家的居家日常**。

## 架构（三层）

```
[ desktop DSH ]
  ├─ amadeus 预设 (Agent Preset，鲸鱼娘人格 + 多句分页规范)
  ├─ 控制面板（sidebar 按钮：网关开关/配对/设备）
  └─ amadeus 网关（独立端口 3444，独立 cookie/CSRF/管理路由 /api/amadeus）

[ 移动端 APP: Amadeus Whale ]  (原生 Compose，com.amadeus.whale)
  ├─ 连接入口（设置页：网关地址 → 测试 → 保存）
  ├─ 选档页（列出 amadeus 会话 / 新建 / 改名 / 删除）
  └─ Galgame 剧场（全屏立绘 + 打字机对话框 + 报告/预览/choice/历史四窗口）
```

## 关键原则

- **不修改 DSH / dsh-mobile 源码**：完全独立项目、独立 npm 包 `dsh-amadeus`、独立 App `com.amadeus.whale`。
- **独立网关**：`/amadeus` 前缀、端口 3444、cookie `amw_*`/`x-amw-csrf`、状态目录 `~/.dsh/amadeus/`，与 dsh-mobile（`/api/mobile-access`、3443）零重名。
- **不是 WebView 壳**：App 原生 Compose 承担全部立绘/交互/动画（Live2D 未来接入）。
- **复用连接与安全思想**（参考 dsh-mobile 的局域网 + 远程通道 + 配对/证书），不搬代码。
- **mode 隔离铁律**：App 只接触 `agentPreset === 'amadeus'` 的会话，其他 mode 绝不混入。

## 多句分页 + 标签协议

模型输出按 Galgame 节奏拆短句，每句 15-30 字独占一行，紧跟 `[[AMW:{...}]]` 标签：

```
呜... 月光照在礁石上呢...
[[AMW:{"mood":"shy","sprite":"shy","voice":"whisper","sfx":"wave","bgm":"rain"}]]
刚才帮你改好的 3 个文件，报告放在小窗口里啦
[[AMW:{"mood":"idle","sprite":"smile","window":"report","windowId":"rpt_123","windowTitle":"今日小报告"}]]
```

- `mood`/`sprite` 驱动立绘；`voice`/`sfx`/`bgm` 驱动音效（voice 本期仅元数据，不朗读）；`window` ∈ {none, report, preview, choice} 驱动窗口。
- 思考/工具调用用 `mood: think/tool` 暗示，不暴露灰字。
- 选项用 `ask_user_question` 工具 → Host 转 Galgame 选项卡。

## Host 插件（`src/`）

真实会话闭环，运行在 DSH Host 进程内（Cordis 插件，服务名 `amadeusAccess`）：

| 模块 | 职责 |
| --- | --- |
| `amadeus-plugin.ts` | 插件入口：注入真实 adapter、注册工具、安装 answerer、开场 prompt |
| `amadeus-extension.ts` | 业务路由（/status、/sessions、/reports、/previews、/choice、/choice/cancel、/workspaces、/stream、/tag/ensure） |
| `amadeus-sessions.ts` | 会话 adapter（`sessionQuery.listSessions` 过滤 agentPreset + `sessionController`） |
| `amadeus-reports.ts` | 报告/预览持久化（`~/.dsh/amadeus/reports.json` / `previews.json`） |
| `amadeus-choices.ts` | choice adapter + `user-questions/request` answerer |
| `amadeus-stream.ts` | `follow()` → SSE 流（segments/choice/ended） |
| `amadeus-tools.ts` | `save_report` / `show_preview` 工具 |
| `amadeus-control.ts` | 控制面板真实状态（running/devices/pairing） |
| `amadeus-remote.ts` | 远程 provider 框架（FRP 优先，frpc 实接后续迭代） |
| `gateway.ts` | 独立安全网关（配对/证书/CSRF/SSE/WebSocket 代理） |

业务 REST 基址：`<gateway>/amadeus/extensions/amadeus/routes`。SSE：`GET <routes>/stream/:sessionId`。管理路由：`/api/amadeus`。

## 移动端 App（`app/amadeus-android/`）

从零重建的原生 Compose 工程（compileSdk 36 / minSdk 29，Coil / OkHttp SSE / kotlinx-serialization / MockWebServer 测试）：

- **启动即 demo 剧场**：无网关/连不上 → 本地 24 句相识剧本（月夜礁石初遇），立绘呼吸 + 背景 Crossfade + 打字机 + 心情点缀 + 环境音，可快进到底。
- **记住状态直连**：连接成功后持久记住，此后启动直接真实选档；手动断开才回 demo。
- **真实会话**：选档页（标题/工作区/时间，改名/删除/新建）→ 剧场（底部输入框打字）→ SSE 短句演出 → 报告/预览/choice/历史窗口。
- **设置**：齿轮 → 全屏设置（网关地址/测试/保存/断开连接 + 环境音/工具进度开关）。

## 当前状态

- Host 会话闭环与 App 剧场均已实现并通过单元测试与代码评审（两分支 `feat/whale-app`、`feat/whale-host` 已合并至 main）。
- **已知缺口（下一阶段）**：
  - App 侧配对/认证/TLS 流程未实现（真实模式在带认证网关下不可达，`health` 会 401 回 demo）。
  - 真机 + 真实 DSH 端到端联调未执行。
  - 远程 provider（frpc 实际进程启停、cpolar、Tailscale）未实接。
  - demo 环境音素材未入库（`res/raw/` 缺失，静音安全降级）。
  - Live2D 接入（`SpriteRenderer` 已抽象，待 Cubism SDK）。

## 相关文档

- [设计 spec](docs/superpowers/specs/2026-08-30-amadeus-whale-mobile-galgame-design.md)
- [App 实施计划](docs/superpowers/plans/2026-08-30-amadeus-whale-app.md)
- [Host 实施计划](docs/superpowers/plans/2026-08-30-amadeus-whale-host.md)
- [独立 GitHub 仓库](https://github.com/saya-ch/dsh-amadeus)