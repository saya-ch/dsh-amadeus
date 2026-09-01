# Amadeus: Whale — 配对 / 认证 / TLS 与 P0/P1 收尾

日期：2026-08-31
状态：设计已确认，待实施
前置：`2026-09-01-Amadeus-产品体验结论.md`（产品权威依据，连接闭环在其基础上补齐）

## 1. 目标与范围

在已完成的双线实现（App 剧场 + Host 真实会话适配）基础上，完成 P0/P1 五项目：

- **P0-1** App 配对 / 认证 / TLS 流程（本 spec 核心）
- **P0-2** 真机 + 真实 DSH 端到端联调（验证性质，实现完成后执行）
- **P1-3** 远程 provider 实接（frpc 实际进程启停 + 公网端点校验）
- **P1-4** demo 环境音素材（`res/raw/` 补三个 CC0/开源音频）
- **P1-5** Host `user-questions/request` 的 sessionId 路由确认与测试补强

现状缺口（必须修复）：App 用裸 OkHttpClient，无 TLS 信任、无 cookie、无 CSRF 头 → 真实网关业务路由全部 401 → 启动 health 失败永远回 Demo，真实模式不可达。

## 2. 关键原则

- 复用 dsh-mobile 的**连接与安全思想**（QR 配对、CA 指纹校验、TLS pinning、native-pair/native-renew、Keystore 凭据、CookieJar+CSRF），不搬代码、不引 dsh-mobile 依赖。
- 不修改 DSH / dsh-mobile 源码；改动仅限本仓库。
- 网关侧认证模型**已实现且不再改动**（`src/gateway.ts`/`access.ts`/`managed-setup.ts`/`http-security.ts`），本期只在 App 侧实现客户端对接，Host 侧仅补测试/微小接线。
- 移动端只接受 **https**（网关默认 `tls.mode='provided'`，TLS edge 是安全基线）；http 明文不支持（与 dsh-mobile 一致）。
- 扫码采用 **legacy Camera API + zxing core**（dsh-mobile 同思路），不引入 CameraX/MLKit，保持 APK 轻量。

## 3. 网关认证模型（契约事实，供 App 对接）

### 3a. 端点与 cookie

- 前缀 `AUTH_PREFIX=/amadeus`；cookie：`amw_session`（会话）、`amw_csrf`（CSRF）、`amw_device`；CSRF 请求头 `x-amw-csrf`。
- 免认证端点：`GET /amadeus/health`（`{ok:true}`）、`/metadata`、`/discovery`、`/ca.cer`（DER 格式 CA，`application/pkix-cert`）、`/pair`、`/login`；`POST /auth/pair`、`/auth/renew`、`/auth/native-pair`、`/auth/native-renew`、`/auth/logout`。
- 认证业务路由（含 `/amadeus/extensions/...` 与 `/status`）：`authorize`（cookie 取 `amw_session`→401 `authentication_failed`）→ 非 GET/HEAD 再 `requireCsrf`（header `x-amw-csrf` 匹配→403）。

### 3b. native 认证（App 专用）

- `POST /auth/native-pair` body `{token, label?}` → 201 `{instanceId, deviceId, deviceToken, deviceExpiresAt, sessionToken, csrfToken, sessionExpiresAt}`（不设 cookie，直接返回 JSON）。
- `POST /auth/native-renew` body `{deviceToken}` → 200 `{instanceId, deviceId, sessionToken, csrfToken, sessionExpiresAt}`。
- 均受限流（429）。token 一次性（pairingTtlMs 默认 120s）；`deviceTtlMs` 持久设备、`sessionTtlMs` 短会话；maxDevices 默认 32。

### 3c. 配对密钥与 QR

- 桌面面板 `POST /api/amadeus/pairing/open` 返回 `appKey = dsh1.<instanceId64hex>.<token43base64url>`、`pairUrl = https://host:port/mobile-access/pair#instance=<64hex>&token=<43char>`、`qrSvg`（二维码）。
- instanceId = 网关自签 CA 的 SHA-256 指纹（64 hex lowercase）。
- App 可扫码或粘贴输入 appKey / 配对 URL，两者解析出同一份 `(origin, instanceId, token)`。

## 4. App 配对 / 认证 / TLS 设计（P0-1）

### 4a. 新文件

`network/` 包下新增（纯 JVM 可单测部分与 Android 层分离）：

- `PairingKey.kt`：`data class PairingKey(instanceId, token)`；`parse(raw)` 接受 appKey（`dsh1.<64hex>.<43>` 正则）或配对 URL（`/mobile-access/pair#instance=...&token=...`，origin 部分仅 https）。
- `GatewayOrigin.kt`：`data class GatewayOrigin(host, port)`，`parse` 只接受裸 https origin（拒绝 path/query/fragment/credentials），`serialized` 规范化为 `https://host[:port]`。
- `PairingScanTarget.kt`（可选并入）：解析结果 `{origin, instanceId, token}`。
- `PinnedTls.kt`：`socketFactory(caDer: ByteArray)` → KeyStore(`setCertificateEntry`) → TrustManagerFactory → SSLContext；`validateCertificate(der, instanceId)`：CA 必须自签名（basicConstraints>=0、subject==issuer、self-verify）+ SHA-256 指纹==instanceId。
- `NativeAuthClient.kt`：`fetchPairingCa(origin)`（bootstrap trust-all 单次，限 16KB）；`pair(origin, token, caDer, instanceId, label)`；`renew(origin, deviceToken, caDer, instanceId)`。严格校验响应 JSON 键集合 + instanceId==expected + token/deviceId pattern + 未来时间戳。
- `DeviceCredentialStore.kt`：Android Keystore AES/GCM（alias `amw_device_v1`）加密存 `{instanceId, deviceToken, deviceExpiresAt, caCertificate(base64 DER)}` 到 SharedPreferences（key 含 gateway_url，按网关分 slot）；`load()` 解密+CA 指纹校验；`clear()`。
- `AmadeusAuthClient.kt`（编排）：`pairWithKey(key, origin)`、`restoreCredential(origin)`、`createSessionClient(origin, credential)` 返回已注入 cookie/CSRF/TLS 的 OkHttpClient。
- `AuthCookieJar.kt`：内存 cookie 保存 `amw_session`/`amw_csrf`；配合 `AuthInterceptor` 对非 GET/HEAD 自动加 `x-amw-csrf` 头。
- 扫码：`ScanActivity.kt`（传统 View 全屏，legacy Camera + zxing 解码，白框取景+提示+关闭）、`QrDecoder.kt`（NV21 帧解码，纯 JVM 单测）。

### 4b. 业务 client 改造

- `AmadeusApi` / `AmadeusStream` 构造改为接收已认证的 `OkHttpClient`（由 `AmadeusAuthClient.createSessionClient` 提供），签名不破坏现有调用（现有测试用裸 client 的地方继续可用）。
- `AppRoot.kt`：原 `remember { OkHttpClient... }` 替换为认证 client 工厂；启动逻辑改为「有 baseUrl → load credential → renew → 成功进真实 / 失败清凭据回 Demo」。

### 4c. 配对流程

1. 设置页「配对」区：扫码按钮（打开 ScanActivity）或粘贴 appKey/URL → `PairingKey.parse` 得 `(origin, instanceId, token)`。
2. 请求 CAMERA 权限（运行时），manifest 加 `<uses-permission CAMERA>` + `<uses-feature camera required=false>` + `ScanActivity` 声明。
3. `fetchPairingCa(origin)` → 校验 CA 指纹==instanceId。
4. `pair(origin, token, caDer, instanceId)` → 校验响应 → `DeviceCredentialStore.save`。
5. 注入会话（CookieJar 存 `amw_session`+`amw_csrf`）→ 自动进入真实模式。

### 4d. 自动续期与恢复

- 冷启动：`prefs.baseUrl` 有 → `credential.load()` → `renew` → 成功进真实；失败（deviceToken 过期/撤销/网络）→ 清凭据回 Demo。
- 会话失效：业务请求 401 → 尝试 `renew` 一次 → 仍失败回 Demo 并提示重新配对。
- 设置页「断开连接」：清 cookie + 清凭据 + 清 baseUrl，回 Demo。

### 4e. 配对 UI 与错误文案

- 设置页显示配对状态（已配对 deviceId 后 8 位 / 未配对）。
- 错误文案区分：token 已过期（需在桌面重新生成 appKey）、设备数达上限（409）、限流（429）、TLS 校验失败（指纹不匹配）、超时（LAN 短超时 vs 远程长超时）、网络不可达。

### 4f. 测试

- 纯 JVM：`PairingKeyTest`、`GatewayOriginTest`、`QrDecoderTest`（用已知 QR 的 YUV 数据）、`PinnedTlsTest`（构造自签 CA 验证指纹/自签名判定）。
- 集成：`NativeAuthClientTest`（MockWebServer 模拟 native-pair/renew 响应，验证严格校验与错误分类）、`DeviceCredentialStoreTest`（Keystore 加密往返，在 Android 环境/或注入 fake KeyStore 抽象）。

## 5. 远程 provider 实接（P1-3）

- 现有 `src/amadeus-remote.ts` 框架（RemoteProvider tailscale/cpolar/frp 单一选择串行持久化、`FrpController` 骨架）已就位。
- 本期实现 **frpc 实际进程启停**：
  - `FrpController`：`setEnabled(true)` 启动 frpc 子进程（可执行路径走配置项，默认探测 `frpc` PATH），`setEnabled(false)` 终止；状态报告进程 PID / 运行中 / 退出码；stdout/stderr 捕获供错误上报。
  - 公网端点校验：启动后按 provider 配置的 URL 做 TCP/HTTP 探测，成功才报 `origin` 可用；失败上报原因。
  - `select()` 切换时先停旧启新（复用现有串行/持久化）。
- Tailscale / cpolar 保持预留接口（不实现）。
- 测试：`FrpController` 用假进程命令（如 `node -e`/`cmd /c` 模拟）验证启停状态机与端点探测；真实 frpc 可执行文件不在仓库，实机联调时验证。

## 6. demo 环境音素材（P1-4）

- `app/amadeus-android/app/src/main/res/raw/` 补三个音频：`sfx_wave`（海浪）、`sfx_bell`（提示铃）、`bgm_rain`（雨声背景）。
- 素材要求：CC0 / 开源许可，来源记录到 assets 目录 README 或 gradle 注释；找不到干净授权素材时保持现有 getIdentifier 静音降级（不阻塞）。
- `AmbientSound` 已用 `getIdentifier` 按名解析，文件就位后自动生效；补一个资源存在性检查测试（或构建期 lint）。

## 7. Host user-questions sessionId 路由确认（P1-5）

- 现状：`AmadeusChoicesAdapter` 的 answerer 已用 `agent.session.id` 桥接到 SSE 流（`registerStream`/`sessionIdOf` 已在 62b8140 实现），choice 取消经 `POST /choice/cancel` → answerer reject。
- 本期补：真实 DSH 服务签名验证（`user-questions/request` waterfall 是否带 `agent.session.id`、`{global:true}` 是否穿透 agent-scoped 过滤）——以测试 + 真实 boot 冒烟形式确认，若不匹配则适配 `sessionIdOf` 取字段。
- 已有 `POST /choice/cancel`（f278d02）与 App `cancelChoice`（41b0b59）配对，补 Host 侧 end-to-end 测试锁链路。

## 8. P0-2 真机 + 真实 DSH 联调（验证项）

实现完成后需真实环境验证（需用户提供真机 + 运行中的 DSH Host）：

1. 扫码配对全流程（桌面生成 QR → App 扫码 → 配对 → 进真实选档）。
2. 真实会话演出：新建会话开场、发消息、SSE 分页演出、报告/预览/choice 窗口闭环、choice 取消。
3. TLS 指纹校验、renew 续期、断开重连。
4. 4MB SSE 上限与 snapshot/page 重复（若触发，记录并评估）。
5. frpc 远程通道（若配置）。

## 9. 范围外（本期不做）

- Tailscale / cpolar provider 实接（仅 frp）。
- TTS 朗读。
- Live2D 接入。
- 美术母版/image2 差分。
- 真实模式环境音（保持静默）。

## 10. 验收标准

- App：配对 → 真实剧场端到端可用（选档/演出/窗口/取消），断开回 Demo，重启自动 renew 恢复，401 自动续期。
- Host：frpc 启停与端点校验通过；P1-5 链路测试全绿；全量测试无新增失败（基线 `mobile-extension.test.ts:333` 已知除外）。
- 素材：三个音频就位，demo 环境音可播放，无声降级不回归。