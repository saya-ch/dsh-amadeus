# Task 1 报告：依赖与纯 JVM 解析层

**状态**: DONE
**Commit**: `ebf566ac1f2a79dea2caeefbdf32523658cf467b`
**分支**: feat/whale-auth

## 概述
按简报 RED→GREEN 流程完成配对认证闭环第 1 任务：实现纯 JVM 解析层 `GatewayOrigin`、`PairingKey`、`PairingScanTarget`，并加入 zxing core 依赖。

## 执行记录

### 测试文件（简报给定，逐字）
- `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/GatewayOriginTest.kt`
- `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/PairingKeyTest.kt`

### RED 阶段
运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.GatewayOriginTest" -q`
→ 预期失败确认：`Unresolved reference 'GatewayOrigin'` / `'PairingKey'`，`compileDebugUnitTestKotlin` 编译错误，BUILD FAILED。

### 实现（简报给定，逐字）
- `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/GatewayOrigin.kt`
- `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PairingKey.kt`
- `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PairingScanTarget.kt`

### GREEN 阶段
运行 `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.*" -q`
→ PASS：GatewayOriginTest 5 tests、PairingKeyTest 4 tests，共 9 个，0 failures / 0 errors。

### zxing 依赖
- `gradle/libs.versions.toml`：`[versions]` 加 `zxing = "3.5.4"`；`[libraries]` 加 `zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }`
- `app/build.gradle.kts`：dependencies 加 `implementation(libs.zxing.core)`

### 编译确认
运行 `.\gradlew.bat :app:compileDebugKotlin -q` → BUILD SUCCESSFUL（无输出）。

## 验证
- 测试：9/9 通过（详见 `app/build/test-results/testDebugUnitTest/TEST-*.xml`）
- 编译：成功

## 备注 / Concerns
- 无功能性 concerns。
- Windows CRLF 警告（LF→CRLF）为已知现象，忽略。
- `PairingScanTarget.parse` 对裸 appKey 抛异常（缺 origin）符合简报设计：扫码产出的是含 origin 的配对 URL；粘贴 appKey 由后续 UI 层接线处理。
- 全部改动仅限 `app/amadeus-android/`，满足全局约束（仅 https、appKey 正则、配对 URL 格式、文件末尾换行）。

---

## Fix（追加）：`PairingScanTarget.parse` 三路解析 bug 修复

**Commit**: `e2ba5c0c77779787880cb17ed971e75c64da4fcd`
**日期**: 2026-08-31

### 修了什么
原实现的路 (a) 裸 origin 检测存在 bug：`GatewayOrigin.parse(trimmed)` 成功后 `throw IllegalArgumentException("origin without pairing key: ...")` 被同一个 `catch (error: IllegalArgumentException)` 捕获并吞掉，导致裸 origin 落入后续分支（走 appKey 分支报 "not an amadeus pairing key"，错误信息错误）。

修复为布尔检测，不再用 catch 吞异常：
```kotlin
val isBareOrigin = try {
  GatewayOrigin.parse(trimmed)
  true
} catch (error: IllegalArgumentException) {
  false
}
if (isBareOrigin) throw IllegalArgumentException("origin without pairing key: $trimmed")
```
`private val PAIR_URL` 常量保留不变。

### 新增测试
- `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/PairingScanTargetTest.kt`（JUnit4，4 个用例）：
  - 合法配对 URL → origin host/port、instanceId、token 全部正确解析
  - 裸 appKey → `assertThrows(IllegalArgumentException)`（缺网关地址）
  - 裸 origin → `assertThrows(IllegalArgumentException)`（缺配对密钥）
  - 畸形 URL（token 长度错误）→ `assertThrows(IllegalArgumentException)`

### 测试命令与输出
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.*" -q
```
→ PASS：GatewayOriginTest 5 + PairingKeyTest 4 + PairingScanTargetTest 4，共 13 个，0 failures / 0 errors。

```
.\gradlew.bat :app:compileDebugKotlin -q
```
→ BUILD SUCCESSFUL（无输出）。
