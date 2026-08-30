# Amadeus: Whale — 移动端 Galgame 剧场设计与真实会话打通

日期：2026-08-30
状态：设计已确认，待实施

## 1. 产品定位

DSH 的自定义高权限模式（Mode）配套独立移动端 App「Amadeus Whale」：用户在手机上以 Galgame 形式与鲸鱼娘人格的 Agent 互动，Agent 在电脑幕后正常执行工作，长文本（报告/结果）通过可点开的窗口查看。

叙事线：**demo 讲用户与鲸鱼娘的相识（海边月夜初遇）；之后每次新建会话都是用户与鲸鱼娘在家的居家日常**。

## 2. 关键原则

- 不修改 DSH / dsh-mobile 源码；完全独立项目、独立仓库 saya-ch/dsh-amadeus、独立 npm 包 dsh-amadeus、独立 Android App `com.amadeus.whale`。
- 不做桌面 Galgame 预览；桌面只有预设可选 + 控制面板。
- 不是 WebView 壳：App 原生 Compose 承担全部立绘/交互/动画（Live2D 未来接入）。
- 复用 dsh-mobile 的**连接与安全思想**（局域网网关 + 远程通道 + 配对/证书/CSRF），不搬代码；独立网关 `/amadeus`、独立端口 3444、独立 cookie `amw_*`、独立状态目录 `~/.dsh/amadeus/`，与 dsh-mobile（`/api/mobile-access`、端口 3443）零重名。

## 3. 标签协议（核心创新）

模型输出按 Galgame 节奏拆短句，每句 15-30 字独占一行，紧跟 `[[AMW:{...}]]` 标签：

- `mood` ∈ {shy, think, tool, happy, sad, idle} → 立绘心情
- `sprite` ∈ {shy, think, tool, wag, gray, smile, talk} → 立绘形象
- `voice` ∈ {whisper, soft, excited} → 本期仅存元数据，不朗读
- `sfx` ∈ {wave, bell, none}、`bgm` ∈ {rain, none} → 环境音（仅 demo 播放）
- `window` ∈ {none, report, preview, choice} + `windowId`/`windowTitle` + `choiceId`/`options`

思考/工具调用不显示灰字，用 `mood: think/tool` 暗示。选项用 `ask_user_question` 工具。模型忘带标签时 Host 用 `ensureAmadeusTag` 兜底。

## 4. 导航与状态流

```
启动 → 是否有已保存网关
  ├─ 无 / 有但 /amadeus/health 连不上 → DemoTheatre（本地 demo）
  └─ 有且 health 可达 → RealTheatre 选档页 → 剧场
```

- **记住状态直连**：连接成功后持久记住，此后启动直接真实选档；仅手动「断开连接」才回 demo；health 连不上回退 demo 并提示。
- **DemoTheatre**：本地 20-30 句开场 demo（月夜礁石/初遇），立绘+背景+环境音全开；每次进 App 从头演，可快进到底。
- **RealTheatre**：先落选档页（竖向列表：标题+所属工作区+最后活动时间；长按=改名/删除；底部=新建会话）；点会话进剧场。
- **剧场**：全屏立绘+底部输入框（打字主交互）+右上角齿轮→全屏设置浮层（网关地址/测试/保存+状态/断开连接/演出偏好开关）。
- **mode 隔离铁律**：选档页只列出 `agentPreset === 'amadeus'` 的会话，任何其他 mode 绝不混入。

## 5. 演出层（剧场表现）

- **立绘**：`StaticSpriteRenderer` 用 Coil `AsyncImage` 从 assets 加载，映射 `shy→whale-shy`、`think→whale-confused`、`tool→whale-serious`、`wag→whale-cheerful`、`gray→whale-frightened`、`smile→whale-starry`、`talk→maid-left`。底部对齐、宽约屏高 60%、`ContentScale.Fit`；呼吸动画（scale 1.00↔1.02，周期 3s）；换 sprite 用 `AnimatedContent` + `slideInVertically` + `fadeIn`。
- **背景**：`Crossfade` 1s 切换。demo 海边月夜（`palace-night` 顶替）；真实模式居家办公素材（`bg-claude-writing-study` / `bg-gpt-collaboration-workshop` 等，缺的从开源仓库 JAdpp/dsh-whale-galgame 补齐）。
- **对话框**：底部半透明深色面板圆角 20dp，名字标签「鲸鱼娘」，打字机效果；未打满点击=立即打满，已打满点击=下一句；右下角「▼」提示；多短句逐次显示。
- **心情点缀**（Canvas+无限动画，零新依赖）：idle/happy 海面光斑、shy 上浮气泡、sad 雨丝。
- **环境音**：仅 demo——海浪/风声/提示铃（MediaPlayer），设置开关；真实模式静默。
- **干活中反馈**：设置项，可选只示思考态（mood=think/tool）或加工具进度。

## 6. 真实会话数据通路（Host 侧）

### 6a. sessions adapter（替换当前空对象 `business: AmadeusGatewayOptions = {}`）

- `list(mode)`：`ctx.sessionQuery.listSessions()` 读全部 SessionHeader，过滤 `agentPreset === 'amadeus'`，映射 `AmadeusSessionSummary{id, title, mode:'amadeus', updatedAt, lastMessage?}`；title 从 `sessionQuery.readTitle()` 取。
- `get(id)`：同过滤后返回单个。
- `create(mode, workspaceId?)`：`ctx.sessionController.create({ workspaceId, agentPreset: 'amadeus' })`。
- `rename`：`sessionController.rename({sessionId, title})`。
- `delete`：无原生删除接口，用 `workspaceRegistry.archiveSession(sessionId)` 归档隐藏。

### 6b. 工作区选择（复用 DSH 接口）

- `workspaceRegistry.list()` 枚举已有工作区供选；允许自行输入路径。
- 默认用预设默认工作区（由插件配置项 `defaultWorkspacePath` 指定，如 `~/amadeus-workspace`，目录不存在则自动创建），无则回退 Host 默认 cwd。
- `sessionController.create` 接受 `workspaceId` 或 `cwd` 二选一（同给报 bad-request）。

### 6c. 消息流（SSE 推送）

- Host 用 `sessionController.follow({address:{kind:'session',sessionId}})` 拿快照+实时事件流。
- 网关开 `GET /amadeus/extensions/amadeus/stream/:sessionId` SSE 端点（复用网关 SSE 基建），`assistant/message` 用 `parseAmadeusSegments` 拆短句+标签 JSON 推 app；同时推 choice 窗口事件。
- 用户输入：app POST → Host `sessionController.prompt({requestId, sessionId, mode:'queue', content:[{type:'text',text}]})`。
- 取消：`sessionController.cancel`。

### 6d. 报告/预览（专用工具）

- Host 用 `ctx.tools.register(defineTool(...))` 注册两工具（写入 preset 的 agent.cordis.yml 对应行）：
  - `save_report`：模型提交 markdown/文件列表 → 存报告库 → 返回 windowId。
  - `show_preview`：模型提交网页 URL/图片/表格/代码 diff → 存预览资产 → 返回 windowId。
- 标签带 `windowId` 时 app 按 id 拉取渲染（报告窗口可滚动 Markdown/文件列表、可在 DSH 打开；预览窗口缩放/全屏）。

### 6e. choice（ask_user_question 接管）

- Host 监听 `'user-questions/request'` waterfall（agent-scoped）作 answerer。
- 收到问题 → 存待决 choice → SSE 推 choice 窗口给 app。
- app 点选 → POST `/choice` → `choices.resolve` → 返回 `AskUserQuestionAnswer` 给 agent。
- 用户关闭 choice 窗口 = 取消（向 userQuestions 抛错让 agent 处理）。

### 6f. 新会话开场（方案 A）

- `create` 成功后立即 `prompt()` 发内部开场指令（如「你刚在月夜礁石边遇见用户，打个招呼吧」），agent 现场生成开场白。
- 说明：DSH 无 preset seed 机制；`sessionController.create` 不接受 seed；`CreateAgentOptions.seed` 仅用于 fork 回放。

### 6g. 历史记录窗口

- 进入已有会话显示**最新态**（Galgame 读档语义，不重播）。
- 剧场另有「历史记录」窗口按键：像正常 web 对话，仅显示最近几条工具/会话等历史，向上翻动分页加载更早（`sessionController.page` 或 `sessionQuery.readSurface`）。

## 7. 网关桥接

### 7a. 局域网

本机网关 3444 监听，设备经局域网 IP + 配对证书接入；`AUTH_PREFIX=/amadeus`、SSE、WebSocket 代理已具备。

### 7b. 远程连接（参考 dsh-mobile 思想，不搬代码）

- 网关支持远程通道：与局域网独立，共享同一配对/设备体系。
- 三选一 provider：**Tailscale Funnel**（内置）/ **cpolar**（国内网络优先）/ **自建 FRP**（VPS+域名）。
- 借鉴 `RemoteProviderController` 生命周期思想：initialize/status/setEnabled/reconnect/reset，单一 provider 持久化选择、串行切换、互斥。
- 本期：远程 provider 框架 + FRP 自建优先，其余 provider 预留接口后续接。
- app 连接地址：局域网 IP 或远程公网 URL，同一网关基址。

### 7c. 持久化

报告/预览/choice 落 `~/.dsh/amadeus/`（reports.json / previews.json / choices.json 或按 id 分文件）；会话本体由 DSH persistence 落盘（不重复存）。

### 7d. 断连与重连

SSE 指数退避重连；running 状态以 `api-session/status` 事件为准；断网回 demo 保留已演内容。

## 8. 桌面端范围

- 确保 amadeus 预设就位（`ensureAmadeusPreset`）。
- 业务 adapter 注入（sessions/reports/choices/工具/choice answerer）。
- 网关照常运行。
- 控制面板实接：现有 `amadeus-client.ts` 的配对/设备/状态接通真实数据。

## 9. 技术边界（现状与缺口）

- Host：Cordis 插件 `dsh-amadeus`，独立服务名 amadeusAccess，独立 cookie `amw_*`/`x-amw-csrf`，独立管理路由 `/api/amadeus`，独立端口 3444，独立状态目录 `~/.dsh/amadeus/`。
- 客户端 bundle：package.json 需 `exports["./client"]` + `dsh.client` 声明（与 dsh-mobile 一致），解决 /plugins 404。
- APP：Jetpack Compose（compileSdk36/minSdk29）；`SpriteRenderer` 接口抽象，现静态切图，未来 Live2D；Coil 依赖已有。
- 现存问题：客户端 bundle 未出现在 boot 图（/plugins 404）；APP 连 demo 数据；会话/报告/choice 未接 DSH 真实存储（业务 adapters 空→503）；TheatreScreen 背景/立绘层被注释、StaticSpriteRenderer 内部全空。

## 10. 范围外（本期不做）

- TTS 朗读（voice 标签仅元数据）。
- 桌面 Galgame 预览。
- 真实模式环境音。
- Live2D 接入（预留接口）。
- 美术母版/image2 差分（本期用现有公开素材）。