# Amadeus: Whale — 项目理解文档

> 创建时间: 2026-08-30
> 基于 DSH Mobile (dsh-mobile@0.3.3) 的 Galgame 化 Amadeus 分支

## 1. 项目一句话

在 DSH 本体上运行一个高权限的自定义 Mode `amadeus`，配套一个独立的移动端 APP，通过复用 `dsh-mobile` 的安全连接层，让用户可以在手机上以 Galgame 的形式与鲸鱼娘人格的 Agent 对话，且 Agent 的每次公开输出都携带标签驱动 APP 的立绘/音效/动画。

用户当前已验证：PC 上已安装 `dsh-mobile` + `dsh-market`，通过 `cpolar` 远程通道用手机与 DSH 会话，QQ音乐等电脑端能力可被远控。躺在床上不动即可完成操作是核心体验。

## 2. 灵感来源

- **命运石之门 Amadeus**：手机里住着的红莉栖记忆体，AI 形态的助手，核心是“记得你、陪着你、傲娇但可靠”。
- **鲸鱼娘 Galgame**：用户希望的最终形态不是聊天列表，而是全屏立绘 + 底部对话框，思考过程与工具调用也以 Galgame 演出（气泡、道具卡片、表情切换）的形式呈现。

## 3. 整体链路

```
[ Android APP: Amadeus Whale ]  --HTTPS/TLS1.2 + 证书固定+配对-->  [ Host 插件 Gateway (Fork dsh-mobile) ]
        |                                                             |
        | 1. GET /amadeus/sessions?mode=amadeus 选档/新建                 | 2. 代理到 DSH 原生 Web/Host
        | 2. WS /amadeus/session/:id/stream 标签流                    |    -> 转发给 Mode=amadeus 的 LLM
        | 3. 解析 [[AMW:{...}]] -> 切立绘/音效/打字机                   |
        v                                                             v
                                                              [ DSH Core ]
                                                                Mode: amadeus
                                                                - sandbox: danger-full-access
                                                                - autoApproveTools: true
                                                                - System Prompt: 鲸鱼娘人格 + 标签铁律
```

## 4. 三大核心模块

### 4.1 高权限 Mode `amadeus`

- **注册位置**：插件 `apply(ctx)` 中 `ctx.modes.register({id: 'amadeus'})`，依赖 `dsh-system-prompt` / `dsh-llm` / `dsh-scope`。
- **权限**：`danger-full-access`，本机用户全权限，工具自动通过审批，保证 Galgame 演出不被打断。
- **会话隔离**：会话创建时写入 `mode=amadeus` 元数据，查询时过滤。物理存储仍在 DSH 统一的 session 仓库。
- **输出劫持**：`ctx.llm.on('before_send_to_user')` 强制校验标签，未带则用规则/小模型补标签，保证 APP 永远能解析。

### 4.2 标签协议 (Tag Protocol)

模型每次公开文本末尾必须追加单行 JSON 标签块，APP 解析后隐藏：

```
公开台词正文...
[[AMW:{"mood":"shy|think|tool|happy|sad|idle","sprite":"shy|think|tool|wag|gray|smile","voice":"whisper|soft|excited","sfx":"wave|bell|none","bgm":"rain|none"}]]
```

- `mood/sprite` 驱动立绘切换
- `voice/sfx/bgm` 驱动音效
- 思考与工具调用不直接暴露，通过 `mood` 暗示（think/tool）
- 兜底：Host 层 `fallbackTagger` 根据文本情绪补标签

### 4.3 移动端 APP (参考 dsh-mobile)

- **Fork 路径**：直接 Fork `dsh-mobile/apps/mobile` + `src/gateway.ts` + `src/access.ts` + `src/storage.ts`。
- **保留**：TLS 证书固定、配对 (扫码/链接/密钥)、发现 (mDNS/UDP)、三远程通道 (tailscale/cpolar/frp) 的 UI 与逻辑。
- **新增**：
  - 首页：会话选档页 `GET /amadeus/sessions?mode=amadeus`
  - 剧场页：全屏立绘层 + 对话框层 + 特效层，WebView 仅负责拉流，解析与渲染在原生层
  - 打字机、表情切换、道具卡片展开

## 5. 与 dsh-mobile 的关系

- `dsh-mobile` = 通用移动适配 + 安全网关，解决“怎么连上”
- `dsh-amadeus` = 垂直 Galgame Mode + 定制 APP，解决“连上后怎么演”
- 连接层代码复用度 >70%，重点改 `mobile.js` 的注入点与 `plugin.ts` 的 Mode 注册。

## 6. 美术与资源 (后续)

- 立绘差分 6 张：base/shy/think/tool/happy/sad/talk，基于同一 seed 用 image2 inpaint 保证一致性
- 背景 2-3 张：月夜礁石/海底/房间
- 对话框、道具图标等 UI 素材
- 资源路径：`app/assets/{sprites,backgrounds,ui}`，`webp` 格式

## 7. MVP 步骤

1.  创建 `dsh-amadeus` 骨架，复用 `dsh-mobile` 的 gateway 与配对
2.  注册 `amadeus` Mode，写入鲸鱼娘 System Prompt + 标签铁律
3.  实现标签剥离与立绘切换的最小闭环（手机新建 amadeus 会话 -> 模型输出带标签 -> APP 切图）
4.  补齐选档页、思考/工具的 Galgame 演出、顺序播放等细节

## 8. 当前状态

- 新项目文件夹 `C:\develop\dsh-amadeus` 已创建
- 理解文档已落盘，后续开发基于此文档展开
