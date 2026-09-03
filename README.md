# Amadeus: Whale

DSH 的 **Galgame Mode**：鲸鱼娘人格的 Agent 在电脑幕后真实执行工作，用户在手机上以剧场形式与她互动（打字机演出 + 立绘表情 + 报告/选项/历史小窗）。配套原生 Compose App「Amadeus Whale」。

> 叙事线：**demo** 讲用户与鲸鱼娘的相识（海边月夜初遇）；**真实会话**是用户与鲸鱼娘在一起的居家日常——每场独立、性格固定、不跨会话记忆。

## 快速理解

```
[ 电脑: DSH Host ]
  ├─ amadeus Mode（鲸鱼娘人格 preset + 「一段输出 = 一次演出」协议）
  ├─ dsh-amadeus 插件（Cordis）：接 agent 事件流 → 转 Galgame 帧
  └─ 安全网关（LAN 直连 :10486 或 cpolar 远程隧道，复用 dsh-mobile 安全层）

[ 手机: Amadeus Whale App ]  原生 Compose（com.amadeus.whale）
  ├─ 标题画面 → demo（首次相识仪式）/ 连接页（扫码配对）
  ├─ 读档页（会话列表 = 存档位，按工作区分组，改名/删除/新建）
  └─ 剧场（全屏立绘 + 打字机对话框 + 事件流 + 四窗口：报告/预览/选项/历史）
```

## 架构

**插件在 DSH Host 进程内**（服务名 `amadeusAccess`），与 dsh-mobile 共享同一套**安全连接层**——不重写网关，而是向共享网关注册自己的 extension 路由。默认 LAN 监听 **10486**（`~/.dsh/amadeus/setup.json` 配置），cpolar 远程隧道复用同源配对。

```
Agent 事件流 (assistant/message, turn/start·end, tool/step/approval …)
   │  amadeus-stream.ts 转帧
   ▼
SSE 帧流 →  /stream/:sessionId  →  App 打字机演出
choice/approval ← POST /choice·cancel /approval ←  App 弹窗
```

**业务 REST 路由**（经 extension 注册在共享网关上，App 实际只走这些）：

| 路由 | 用途 |
| --- | --- |
| `GET /sessions` `POST /sessions` | 列会话（读档页）/ 新建（默认工作区） |
| `GET /sessions/:id` 等 | 改名 / 删除 / 历史分页 |
| `GET /workspaces[/browse]` `POST /workspaces/register` | 工作区列表 / 目录浏览 / 注册（读档页选目录） |
| `GET /stream/:sessionId` | SSE 帧流（dialogue / choice / activity / turn / ended） |
| `POST /choice` `/choice/cancel` | 选项选中 / 暂时不选（通知 agent 取消，防挂死） |
| `POST /approval` | 审批答复（批准/拒绝） |
| `GET /reports` `/previews` | 报告 / 预览（长文本工作交付物） |
| `POST /tag/ensure` | （内部）标签同步 |

> 管理面板路由 `/api/amadeus`（status/control/pairing）供 DSH web 控制面板用；配对认证细节见 docs。

## 插件源码布局（`src/`）

| 模块 | 职责 |
| --- | --- |
| `amadeus-plugin.ts` | 入口：注入 adapter、开场注入、组装网关 extension |
| `amadeus-extension.ts` | 业务 REST 路由注册（上表） |
| `amadeus-stream.ts` | `follow()` 事件流 → SSE 帧（对话/工作/回合边界） |
| `amadeus-sessions.ts` | 会话 adapter（列/建/改/删/历史） |
| `amadeus-session-registry.ts` | 会话归属判定落盘缓存（避免每次 list 全量读 surface） |
| `amadeus-reports.ts` / `amadeus-tools.ts` | 报告/预览持久化 + `save_report`/`show_preview` 工具 |
| `amadeus-choices.ts` | `ask_user_question` answerer（`{global,prepend}` 抢占）→ App 选项卡 |
| `amadeus-approval.ts` | 审批 answerer（`approval/request` → App 决定） |
| `amadeus-control.ts` | 控制面板（running/devices/pairing） |
| `amadeus-remote.ts` 等 | 远程 provider（frpc/cpolar/tailscale）框架 |
| `gateway.ts` | 复用 dsh-mobile 安全网关的注册入口 |

构建：`npm run build` → `lib/index.mjs`（Host 插件）+ `lib/client.js`（web 控制面板，平台 web）。测试：`npx vitest run`（12 文件 / 97 tests，含 approval/choices/stream/sessions/remote）。

## Mode / preset

`presets/amadeus/` 定义鲸鱼娘人格（agent-plane 配置）。铁律：**一段输出 = 一次演出**——每次回复 2–4 个短句（每句 15–30 字、句间换行），段尾**一组**标签驱动整段演出状态：

```
呜... 月光照在礁石上呢... 有你在身边，感觉暖暖的 啾~
[[AMW:{"sprite":"happy"}]]
```

- 标签**只需 sprite 一个字段**（voice/window 为预留元数据，本期不消费）。
- sprite 9 值：`normal` `excited` `happy` `shy` `thinking` `exclaim` `pout` `deadpan` `flustered`（女仆装立绘整图切换，见下方素材）。
- 长文本不进对话框：超长内容要求 `save_report`/`show_preview` 存窗口，对话框只留鲸鱼娘口吻摘要（Host 兜底截断）。
- 思考/工具调用不暴露为灰字台词：`turn/end` 边界、`activity` 帧驱动 App 的事件流浮字（文本框上方滚动的幕后活动），回合结束统一淡出。
- 选项不自己写，用 `ask_user_question` → Host 转 Galgame 选项卡。

## 移动端 App（`app/amadeus-android/`）

原生 Compose（compileSdk 36 / minSdk 29 / target 36；Coil 加载立绘、OkHttp SSE、kotlinx-serialization、MockWebServer 测试）。

**流程**：标题画面 → 首次设备播 demo（相识剧本，可重放）→ 连接页（扫码/手动配对）→ 读档页（会话 = 存档位，按工作区分组倒序，新建 = "开启新的一天"，可选工作区目录）→ 剧场。有已存网关时启动直进最新会话，剧场内一键切换进读档页。

**剧场演出**：
- 打字机（标点停顿：逗号 +90ms / 句号叹号 +135ms / 省略号 +225ms），点击立即打满。
- 立绘整图随 sprite 切换（Crossfade 过渡），**保留上一轮表情**（用户发言期间不回 normal）。
- 9 表情素材：`assets/amadeus/whale-*.webp`（excited/happy/shy/thinking/exclaim/pout/deadpan/flustered/normal），装饰素材 `maid-*.webp`。
- 文本框上方右侧**事件流**：幕后活动滚动浮字（黑字白描边、单行、上限 5 条、9s 淡出），回合结束统一渐隐。
- 输入框角落按键展开（不挤对话）；发送时震动。
- 对话句末 ▼（等待）/ 三点脉动 working（任务进行中）；`turn/end` 到达 = 回合边界。
- 四窗口：报告（长文本交付物）/ 预览 / 选项（ask_user_question 卡片，居中限高可滚，可"暂时不选"取消）/ 历史（透明侧栏，只含演出文本）。

**配对**：扫码（CameraX + ZXing）或手动 URL。首次用 trust-all client 拉 `ca.cer` pin 后切换受信 client。凭据存 Android Keystore（alias `amw_device_v1`）+ 文件，按网关 origin 隔离——**换域名/端口需重新配对**。远程（cpolar）与 LAN 共享同一配对窗口。

**视觉**：主题两套（暖色治愈默认 / 深色），叠加深蓝×金×女仆蕾丝装饰层（装饰不随主题变，C 方案做显眼）。字体：马路口圆体（Maruko Gothic CJK SC，SIL OFL 1.1 免费可商用，随包附许可）。

**Demo**：首次设备播一次（清数据才重放），设置可重放；24 句月夜初遇剧本本地演出。

## 当前状态

**已实现并真机验证**：Host 会话闭环（建会话→Agent 干活→SSE 演出→报告/选项/审批全链路）、App 剧场全部演出层、配对/认证/TLS（LAN 直连 + cpolar 远程）、读档页按工作区组织、事件流、回合边界信号、choice 取消防挂死、会话注册表缓存。Host 97 tests 全绿。

**已知缺口（见产品文档第 3 节）**：
- 真实会话开场白（方案已定：Host/preset 自动注入一句固定问候，未写文案）。
- BGM 未做（`res/raw/` 为静音占位 WAV，方案：CC0 素材替换 + 接播放器）。
- 专属 logo 未生成（现 🐳 emoji + 标题文字）。
- 表情差分（动作 × 表情矩阵，GPT image2 分批扩充）未开始；背景库缺居家室内。
- 远程 provider 的 frpc 实机启停未联调（cpolar 已实机验证）。
- Live2D 预留接口，本期不做。

## 素材与合规

- 全部美术来自 DSH 社区开源项目（**CC BY-NC-SA 4.0**，非商用），逐文件来源/署名链见 `app/.../assets/amadeus/SOURCES.md`。
- 源素材备份在 `app/assets/`（sprites/backgrounds）；打包实际用 `assets/amadeus/`。
- 字体马路口圆体 OFL 许可：`assets/licenses/`。
- 注意：CC BY-NC-SA 素材限非商业使用——若项目转商用需替换素材。

## 相关文档

- [产品体验结论（权威，含决策史 + 实现快照）](docs/superpowers/specs/2026-09-01-Amadeus-产品体验结论.md)
- [App 架构设计](docs/superpowers/specs/2026-09-01-Amadeus-App架构设计.md)
- [配对认证与 TLS 设计](docs/superpowers/specs/2026-08-31-Amadeus-配对认证与TLS设计.md)
- [早期实施计划](docs/superpowers/plans/)（app / host 两篇）

## 开发备忘

- 插件改 `src/` → `npm run build` → 重启 DSH 生效；App 改 Kotlin → `cd app/amadeus-android && ./gradlew :app:assembleDebug` → `adb install -r`。
- 本地 admin：`curl http://127.0.0.1:3080/api/amadeus/status`（DSH 跑着才有）。
- 会话判定：`agentPreset === 'amadeus'` 直判；历史/web 会话靠注册表缓存读 surface（`~/.dsh/amadeus/session-registry.json`）。
