# Amadeus — App 技术架构（从零重写）

日期：2026-09-01
状态：架构讨论进行中，随讨论逐节补充；定稿后作为实施基准
性质：**产品文档的实现侧**——记录“怎么实现”，产品“要什么”见 [2026-09-01-Amadeus-产品体验结论.md](./2026-09-01-Amadeus-产品体验结论.md)

---

## 0. 总原则

- **从零重写**：现有 App 代码（39 文件/2967 行）整体舍弃，不重构——按产品文档一步到位。
- **范围**：App 从零重写；**Host 侧（dsh-amadeus npm 包）不重写**——契约已修好、E2E 通过，只微调 mode 行为（`[[AMW:]]` 一组标签协议、长文本铁律 system prompt，见 3.8/产品 1.9）。
- **分层**：presentation / domain / data / platform 四层，UI 不碰网络存储、domain 不碰 Android、数据层只管协议。
- **只保留**：技术栈（Kotlin 2.0.20 / Compose BOM 2024.10 / Material3 / Coil / OkHttp+SSE / kotlinx-serialization / coroutines / zxing）、assets 素材（8 立绘 + 4 背景 + 3 音效）、双 Activity（Main + Scan）。

## 1. 技术栈（保留 + 新增）

| 依赖 | 版本 | 用途 | 状态 |
|---|---|---|---|
| Kotlin | 2.0.20 | 语言 | 保留 |
| Compose BOM | 2024.10.01 | UI | 保留 |
| Material3 | BOM | 标准组件 | 保留 |
| Coil | 2.6.0 | 立绘/背景加载 | 保留 |
| OkHttp + okhttp-sse | 4.12.0 | REST + SSE | 保留 |
| kotlinx-serialization | 1.7.3 | JSON 协议 | 保留 |
| coroutines | 1.9.0 | 异步 | 保留 |
| zxing | 3.5.4 | 扫码解码 | 保留 |
| DataStore Preferences | 1.1.x（新增） | 设备级键值偏好 | **新增** |

## 2. 分层架构

```
┌─ presentation（Compose UI）
│  ├─ theme/          # Material3 + 自定义色板 + 过渡 spec（产品 1.14/1.15）
│  ├─ screen/         # 页面级：Title / Connection / SaveSlot / Theatre
│  ├─ overlay/        # 覆盖层：Settings / Report / Preview / Choice / EventSheet / DialogRecord
│  ├─ theatre/        # 剧场核心：Stage / DialogueBox / NamePlate / Typewriter / Sprite / InputBar
│  └─ root/           # AppRoot：导航状态机 + 主题注入 + 全局状态
├─ domain（业务逻辑）
│  ├─ model/          # AmadeusTag / Segment / Choice / Report / Preview ...
│  ├─ feed/           # MessageFeed 接口 + DemoFeed / RealFeed
│  ├─ session/        # 会话状态机（活动/等待/干活中/出错）
│  └─ settings/       # 演出偏好 + 连接配置
├─ data（数据/网络）
│  ├─ api/            # AmadeusApi（REST）
│  ├─ stream/         # SSE
│  ├─ pairing/        # 配对/证书/凭据
│  └─ store/          # 设备状态（DataStore）
└─ platform（Android 原生）
   ├─ audio/          # BGM 播放（用户可选/音量）
   ├─ haptics/        # 触觉（仅发送）
   ├─ notify/         # 后台通知
   └─ scan/           # 扫码（ScanActivity）
```

核心原则：UI 不碰网络/存储（经 domain 层）、domain 不碰 Android（可单测）、数据层只管协议。

## 3. 已确认的技术决策

### 3.1 导航：混合（自建轻量路由 + 覆盖层叠）

- **页面级流转**用**自建轻量路由**：`sealed class Screen`（Title/Demo/Connection/Theatre 等）+ `MutableStateFlow<Screen>`，AppRoot 分发。不引入 navigation-compose（页面少、覆盖层多，场景切换本质，自建足够）。
- **覆盖层**（设置/窗口/小窗）用 OverlayHost 层叠（见 3.12），不打断剧场演出。
- 原因：galgame 的“设置/窗口/小窗”是浮在剧场上层的纱，不是独立页面；页面级流转才是真导航。

### 3.2 主题系统：Material3 + 自定义色板，运行时切换

- **数据流**：主题状态全局持有（root state/ViewModel）+ 持久化到 DataStore；UI collect → `MaterialTheme` + `LocalAmadeusColors`（CompositionLocal）双通道注入 → 切换即重组。
- **默认主题**：暖色治愈（产品 1.14）；设置里可切换其他主题（深色/冷调等，未来扩充）。
- **覆盖范围**：所有 UI 组件（对话框/读档页/设置/窗口/连接页）跟随所选主题。

### 3.3 过渡语言：统一动画 spec（封装原语）

- **两个过渡原语**（产品 1.15）：
  - `AmadeusOverlay`：覆盖层统一"边缘滑入 + 淡入"（250ms）。
  - `AmadeusCrossfade`：内容切换统一"短 Crossfade + 轻微缩放"（300ms）。
- 组件用封装的原语；特殊场景用常量自拼（如打字机）。

### 3.4 设备状态：DataStore Preferences

- 键值偏好：`demo_seen` / 网关 / 主题 / BGM / 背景 / 文字速度 / 工具进度开关 / 触觉开关。
- **Flow 响应式**：设置里改 → 剧场/读档页/标题画面立即响应。
- 新增依赖 `androidx.datastore:datastore-preferences`。

### 3.5 扫码：独立 ScanActivity（zxing）

- 保留独立 `ScanActivity`（系统相机预览 + zxing 解码），连接页/设置子流程调用。
- 不引入 CameraX（扫码是低频流程，独立 Activity 简单可靠）。

### 3.6 网络层：Repository 模式

- **domain 定义接口**（`SessionRepository` / `WindowRepository` / `ChoiceRepository` / `PromptRepository`），UI/feed/会话状态机只依赖接口，可单测（Mock 接口）。
- **data 层实现**：OkHttp 实现各 Repository（REST 映射）。
- 结构：domain 接口 ← data 实现，UI 不直接碰 OkHttp。

### 3.7 配对/凭据层：收敛成三块

- **`CredentialStore`**（domain 接口）：保存/读取/清除设备凭据（deviceId/sessionToken/csrf），data 层用 DataStore 实现。
- **`AuthService`**（domain 接口）：配对（native-pair/扫码配对）、恢复、凭据刷新、断开；内部用 OkHttp + PinnedTls。
- **`HttpSecurity`**（data 工具）：CookieJar + Interceptor + TLS 固定，一个 OkHttpClient 工厂。
- 收敛原则：domain 只管“配对/恢复/断开”语义，细节（cookie/证书/拦截）收在 data 层。

### 3.8 演出段与打字机：一次文本事件 = 一次展示（无翻页）

- **协议简化（mode 层）**：一个 `assistant/message` = 一段完整文本 + **一组 `[[AMW:]]` 标签**（段尾，描述整段 mood/sprite/voice/window 等）。**不在 message 内拆分多标签**。
- **一次 message = 一次展示**：整段文本打字机演出，标签一次性决定立绘/背景/窗口（段开始即设置，段内不变）。
- **没有“点击下一页”交互**：呼吸▼ 仅提示“正在等待/有内容”，不是翻页按钮；点击对话框仅用于“立即打满当前段”（打断打字）。
- 节奏完全由 agent 输出驱动（产品 1.5.2：不人为句间停顿，块到了就展示）。
- 与打字机参数（1.5.2：45ms/字 + 标点停顿 + 三档速度）配合：段内标点停顿，段间靠 agent 输出间隔。

### 3.9 事件日志：一份 SessionLog + 两个过滤视图

- **单一数据源**：SSE 事件全量存一份 `SessionLog`（append-only，带类型：assistant/思考/tool/step/turn/user 等）。
- **事件流侧栏** = 全量视图（含思考/工具，产品 1.9）；**对话记录侧栏** = 文本过滤视图（只演出文本，产品 1.9）。
- 两视图共用同一份日志，避免双维护；UI 层做过滤。
- 分页：两者都默认最近 10 条，上滑加载更早（产品 1.10/1.9）。

### 3.10 会话状态机：日志为真相源，状态为派生

- **`SessionLog` = 事实记录**（append-only 事件列表）——真相源。
- **`SessionState` = 派生状态**（Idle/Working/WaitingChoice/Error + 当前展示段/立绘/背景）——表现源。
- 状态机**订阅**日志：日志追加 → 状态机更新；不维护两份独立状态。
- 恢复会话（进程被杀，产品 1.10）→ 加载历史 → 重建 SessionLog → 状态机从日志末端派生状态，事件流/对话记录侧栏**自动**恢复。

### 3.11 剧场组织：拆组件 + 状态提升

- 剧场拆成独立 composable，各管一个职责：`TheatreStage`（背景+立绘）/ `DialogueBox`（对话框）/ `InputBar`（输入唤出）/ `OverlayHost`（覆盖层）。
- **状态提升**：各组件纯展示（无内部状态逻辑），状态统一从 ViewModel（或 SessionState）来，组件可独立测试。

### 3.12 覆盖层：统一 OverlayHost

- 一个 `OverlayHost(overlay: OverlayState?)` 接收当前覆盖层状态，统一渲染 + 统一过渡（用 3.3 `AmadeusOverlay` 原语）。
- 覆盖层类型：设置（分页）/ 事件流侧栏 / 对话记录侧栏 / 报告 / 预览 / 选项。
- **单层不叠加**（galgame 中覆盖层很少套娃；如报告里开预览的套娃后置）。
- 层叠顺序、过渡、遮罩（TapTrap）统一由 OverlayHost 管理。

### 3.13 启动流程：启动 ViewModel 决策 + 标题画面掩护

- **`AppLaunchViewModel`**：启动时读设备状态（DataStore）→ 决策初始 Screen（产品 1.10/1.12）：
  - 首次（demo 未看过）→ Demo 剧场 → 播完 → 连接页（配对引导）。
  - 已看过 demo + 无网关 → 连接页。
  - 已看过 demo + 有网关 → 尝试恢复 → 成功：剧场（最后活动会话）/ 失败：连接页。
- **标题画面（logo 淡入）兼作决策加载态**：决策期间显示标题画面，决策完平滑切到目标——不白屏。
- 决策逻辑在 ViewModel（可单测），AppRoot 只 collect。

### 3.14 设置页：一个 SettingsScreen + 分页

- **设置作为一个覆盖层**（3.12 OverlayHost 管），内部分页：**演出**（文字速度/背景选择/BGM 选择音量/工具进度/demo 重放）+ **连接**（网关/配对/断开）——产品 1.10 合并齿轮分页。
- 数据来自 `SettingsViewModel`：读 DataStore 偏好（主题/BGM/背景/文字速度/工具进度）+ 连接状态（网关/配对）。
- 设置里改 → DataStore 写入 → 响应式（Flow）立即生效（剧场/读档/标题跟随）。

### 3.15 读档页：页面级（路由 Screen）

- **读档页是页面级**（3.1 路由的 Screen），独立场景：从剧场切换会话时进入（产品 1.10）。
- 布局：**按工作区分组 + 组内倒序**（最后活动在前）+ 新建会话仪式感（“开启新的一天”）+ 长按改名/删除（轻量样式）。
- 数据：`SessionRepository.list()`（amadeus mode 过滤）→ 按工作区分组。
- 新建会话：默认工作区 + 文件选择器选工作区（产品 1.10）。

### 3.16 连接页：连接剧场（页面级 + 演出形态）

- **连接页本身是“连接剧场”**（页面级 Screen）：背景 + 立绘 + 鲸鱼娘演出“等你接入”——不是技术设置页。
- **配对过程 = 演出“她在等你连上”**；**配对成功 = 演出“她回来了”**（叙事反馈），然后进会话。
- **两种场景，去向不同**（产品 1.12）：
  - 首次配对引导（demo 后）→ 配对成功 → **新建会话**（“她刚住进来”）。
  - 日常重连（非 demo，没网关时进连接页）→ 配对成功 → **最后活动会话**（续聊）。
- 连接页与设置连接分页是**两套 UI**（首屏偏仪式，设置偏工具；逻辑层共享 AuthService，表现层分离）。

### 3.17 platform 层：BGM / 触觉 / 通知

- **BGM**：`BgmPlayer` 封装（选曲/循环/停止/音量/关闭），内部 MediaPlayer——产品 1.11，用户可选曲目 + 音量 + 关闭；不加 ExoPlayer。
- **触觉**：Vibrator（`VibrationEffect.createOneShot` 短震），仅发送消息时触发（产品 1.11）；设置里可关（触觉开关）。
- **后台通知**：纯通知不保活（产品 1.10）——SSE 退后台**不主动断**（被动等系统杀），短时间退后台收到选择/完成事件发通知；进程被杀靠恢复策略兜底（3.10）。不用前台服务。

### 3.18 Host 微调范围（不改架构）

- **协议简化**（配合 3.8/3.19）：mode system prompt 改为“一段输出末尾放**一组标签**描述整段”（不再多标签拆分）；`ensureAmadeusTag` 兜底保留（防漏标签）。
- **长文本铁律**（产品 1.9）：system prompt 加规则“超长内容走 `save_report`/`show_preview`，对话框只留 1~2 句摘要”；Host 加**超长检测拦截**（兜底）：
  - **判定**：`len > 120 字符 或 句数 > 4` → 超长。
  - **处理**：截断保留前 1~2 句 + “…”进对话框，全文自动存成报告窗口（不调模型生成摘要）。
- **开场固定脚本**（产品 1.3/1.4）：开场从“create 后 prompt 模型生成”改为“**create 后 prompt 发固定开场词**（固定脚本 2~3 句轮换，模型以鲸鱼娘身份回应）”，改动最小，稳定性优先。

### 3.19 SSE 事件契约（App ↔ Host 接缝）

四类帧（JSON `data:` 行）：

```
# 演出帧（对话框主角）：一个 assistant/message = 一个展示
{ "type": "dialogue",
  "text": "干净文本（无标签）",
  "tag": { "mood": "happy", "sprite": "wag", ... } }

# 幕后帧（事件流小窗）：思考/工具/step 等
{ "type": "activity",
  "kind": "tool" | "think" | "step" | "turn" | ...,
  "title": "调用工具 foo",
  "detail": "..." }

# 选项帧（ask_user_question）
{ "type": "choice",
  "choiceId": "...", "question": "...", "options": [{label, description}] }

# 结束帧
{ "type": "ended", "reason": "..." }
```

- **`dialogue`**：一个 message 一个帧，Host 已剥离标签成 `tag` 对象（配合 3.8 协议简化），App 不再拆标签。
- **`activity`**：承载思考/工具/step 事件，喂事件流小窗（产品 1.5/1.9）——现有契约缺失，新增。
- **`choice`/`ended`** 保留。

### 3.20 恢复会话数据流（进程被杀后回来）

- **扩展 page 返回原始事件**：`page` 不再只返回纯文本 messages，而是返回**原始 follow 事件**（assistant/message、tool/call、tool/result、思考类…），App 重建完整 SessionLog（演出段 + 事件流都有）。
- 恢复流程：进入会话 → `page`（默认 10 条，上滑分页）→ 重建 SessionLog → 状态机从日志末端派生（最新一条文本 = 当前展示段）→ 打开 SSE（3.19）续接实时。
- 事件流小窗历史也恢复（不是从空开始），符合“日志为真相源”（3.10）。
