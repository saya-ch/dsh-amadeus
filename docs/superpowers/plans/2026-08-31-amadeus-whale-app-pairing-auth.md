# Amadeus Whale App 配对 / 认证 / TLS 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Android App 补上完整配对/认证/TLS 闭环：扫码/粘贴 appKey → CA 指纹校验 → native-pair → Keystore 存凭据 → 认证 OkHttpClient（cookie+CSRF）→ 冷启动 renew 恢复 → 真实模式可达。

**Architecture:** 纯 JVM 解析/解码/TLS 层（`PairingKey`/`GatewayOrigin`/`QrDecoder`/`PinnedTls`）与 Android 层（`ScanActivity`/`DeviceCredentialStore`/`AmadeusAuthClient` 编排）分离。现有 `AmadeusApi`/`AmadeusStream` 构造不变（仍收 `OkHttpClient`），AppRoot 用认证 client 工厂替换裸 client。

**Tech Stack:** Kotlin 2.0.20 / Compose BOM 2024.10.01 / okhttp 4.12.0 / kotlinx-serialization 1.7.3 / zxing core 3.5.4 / Android Keystore AES-GCM / legacy Camera API。

**工作目录:** `app/amadeus-android/`（相对仓库根）。构建/测试命令均在 `app/amadeus-android/` 下运行（Gradle：`.\gradlew.bat`）。

## Global Constraints

- 所有改动仅限 `app/amadeus-android/`；不触碰 Host (`src/`)、不触碰 DSH/dsh-mobile 源码。
- 移动端只接受 **https**：`GatewayOrigin.parse` 拒绝非 https（spec 2/3c）。
- instanceId 正则 `/^[a-f0-9]{64}$/`；deviceId 32 hex；token/appKey token 43 base64url（`[A-Za-z0-9_-]{43}`）。
- appKey 正则：`^dsh1\.([a-f0-9]{64})\.([A-Za-z0-9_-]{43})$`；配对 URL：`https://host:port/mobile-access/pair#instance=<64hex>&token=<43char>`。
- cookie 名 `amw_session`/`amw_csrf`，CSRF 请求头 `x-amw-csrf`，仅非 GET/HEAD 加 CSRF 头。
- CA 校验：自签名（basicConstraints>=0、subject==issuer、self-verify）+ SHA-256 指纹==instanceId。
- Keystore alias `amw_device_v1`，AES/GCM，SharedPreferences 名 `amadeus_device`。
- native-pair 响应 7 键：instanceId/deviceId/deviceToken/deviceExpiresAt/sessionToken/csrfToken/sessionExpiresAt；native-renew 响应 5 键：instanceId/deviceId/sessionToken/csrfToken/sessionExpiresAt。严格校验键集合。
- bootstrap（ca.cer/discovery/metadata）用 trust-all SSLContext，限 16KB；`/ca.cer` Content-Type `application/pkix-cert`，返回 DER 字节。
- App 单测跑 JVM 单测：`.\gradlew.bat :app:testDebugUnitTest`；编译：`:app:compileDebugKotlin`。
- 不新增无必要依赖；zxing 只加 core（不含 android-integration）。
- 文件末尾加换行；每任务单提交。

---

### Task 1: 依赖与纯 JVM 解析层（GatewayOrigin + PairingKey + PairingScanTarget）

**Files:**
- Modify: `app/amadeus-android/gradle/libs.versions.toml`
- Modify: `app/amadeus-android/app/build.gradle.kts`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/GatewayOrigin.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PairingKey.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PairingScanTarget.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/GatewayOriginTest.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/PairingKeyTest.kt`

**Interfaces:**
- Produces:
  - `data class GatewayOrigin(val host: String, val port: Int)` with `val serialized: String` (`https://host[:port]`, port 443 omitted) and `companion fun parse(raw: String): GatewayOrigin` (throws `IllegalArgumentException` on non-https / path / query / fragment / credentials).
  - `data class PairingKey(val instanceId: String, val token: String)` with `companion fun parse(raw: String): PairingKey` (accepts appKey `dsh1.<64hex>.<43>`; throws on malformed).
  - `data class PairingScanTarget(val origin: GatewayOrigin, val instanceId: String, val token: String)` with `companion fun parse(raw: String): PairingScanTarget` — first try `GatewayOrigin.parse(raw)` alone; if that throws, try `PairingKey.parse` (needs origin separately); if raw is a pairing URL `https://.../mobile-access/pair#instance=<64hex>&token=<43>`, extract origin + fragment params. `PairingKey.parse` stays strict appKey-only; `PairingScanTarget.parse` is the union entry.

- [ ] **Step 1: 写失败测试（GatewayOriginTest）**

```kotlin
package com.amadeus.whale.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayOriginTest {
  @Test fun parsesPlainHttpsOrigin() {
    val origin = GatewayOrigin.parse("https://192.168.1.20:3444")
    assertEquals("192.168.1.20", origin.host)
    assertEquals(3444, origin.port)
    assertEquals("https://192.168.1.20:3444", origin.serialized)
  }

  @Test fun omitsDefaultPort() {
    assertEquals("https://dsh.example.com", GatewayOrigin.parse("https://dsh.example.com:443").serialized)
  }

  @Test fun rejectsNonHttps() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("http://192.168.1.20:3444") }
  }

  @Test fun rejectsPathQueryFragmentAndCredentials() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444/path") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444?q=1") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://h:3444#frag") }
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("https://user:pass@h:3444") }
  }

  @Test fun rejectsBareHostWithoutScheme() {
    assertThrows(IllegalArgumentException::class.java) { GatewayOrigin.parse("192.168.1.20:3444") }
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.GatewayOriginTest" -q`
Expected: FAIL（`Unresolved reference: GatewayOrigin`）。

- [ ] **Step 3: 实现 GatewayOrigin.kt**

```kotlin
package com.amadeus.whale.pairing

data class GatewayOrigin(val host: String, val port: Int) {
  val serialized: String
    get() = if (port == 443) "https://$host" else "https://$host:$port"

  companion object {
    fun parse(raw: String): GatewayOrigin {
      val url = try {
        java.net.URI(raw)
      } catch (error: Exception) {
        throw IllegalArgumentException("invalid origin: $raw", error)
      }
      if (url.scheme != "https") throw IllegalArgumentException("origin must use https")
      if (url.rawPath.isNotEmpty() && url.rawPath != "/") throw IllegalArgumentException("origin must not have a path")
      if (url.rawQuery != null) throw IllegalArgumentException("origin must not have a query")
      if (url.rawFragment != null) throw IllegalArgumentException("origin must not have a fragment")
      if (url.rawUserInfo != null) throw IllegalArgumentException("origin must not have credentials")
      val host = url.host ?: throw IllegalArgumentException("origin must have a host")
      val port = if (url.port == -1) 443 else url.port
      return GatewayOrigin(host, port)
    }
  }
}
```

注意：`URI("https://h:3444")` 对合法 origin 应成功。对 `"192.168.1.20:3444"`（无 scheme）`URI` 解析可能当作 opaque 或抛异常——用 `url.scheme != "https"` 兜底拒绝。若 `URI` 对某些输入抛 `URISyntaxException`，被 catch 转 `IllegalArgumentException`。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.GatewayOriginTest" -q`
Expected: PASS（5 tests）。

- [ ] **Step 5: 写失败测试（PairingKeyTest）**

```kotlin
package com.amadeus.whale.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingKeyTest {
  private val instanceId = "a".repeat(64)
  private val token = "A".repeat(43)

  @Test fun parsesAppKey() {
    val key = PairingKey.parse("dsh1.$instanceId.$token")
    assertEquals(instanceId, key.instanceId)
    assertEquals(token, key.token)
  }

  @Test fun rejectsWrongInstanceIdLength() {
    assertThrows(IllegalArgumentException::class.java) { PairingKey.parse("dsh1.${"a".repeat(63)}.$token") }
  }

  @Test fun rejectsBadTokenChars() {
    assertThrows(IllegalArgumentException::class.java) {
      PairingKey.parse("dsh1.$instanceId.${"A".repeat(42)}+")
    }
  }

  @Test fun rejectsMissingPrefix() {
    assertThrows(IllegalArgumentException::class.java) { PairingKey.parse("$instanceId.$token") }
  }
}
```

- [ ] **Step 6: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.PairingKeyTest" -q`
Expected: FAIL。

- [ ] **Step 7: 实现 PairingKey.kt + PairingScanTarget.kt**

```kotlin
package com.amadeus.whale.pairing

data class PairingKey(val instanceId: String, val token: String) {
  companion object {
    private val APP_KEY = Regex("^dsh1\\.([a-f0-9]{64})\\.([A-Za-z0-9_-]{43})$")

    fun parse(raw: String): PairingKey {
      val match = APP_KEY.matchEntire(raw.trim())
        ?: throw IllegalArgumentException("not an amadeus pairing key")
      return PairingKey(match.groupValues[1], match.groupValues[2])
    }
  }
}
```

```kotlin
package com.amadeus.whale.pairing

data class PairingScanTarget(val origin: GatewayOrigin, val instanceId: String, val token: String) {
  companion object {
    private val PAIR_URL = Regex("^https://([^/?#]+)/mobile-access/pair#instance=([a-f0-9]{64})&token=([A-Za-z0-9_-]{43})$")

    fun parse(raw: String): PairingScanTarget {
      val trimmed = raw.trim()
      PAIR_URL.matchEntire(trimmed)?.let { m ->
        val origin = GatewayOrigin.parse("https://${m.groupValues[1]}")
        return PairingScanTarget(origin, m.groupValues[2], m.groupValues[3])
      }
      // Fall back to a bare pairing key: origin is unknown until paired, so require it separately.
      PairingKey.parse(trimmed) // throws on malformed; validates instanceId/token shapes
      throw IllegalArgumentException("a bare appKey needs the gateway address; use a pairing URL or scan")
    }
  }
}
```

注：`PairingScanTarget.parse` 对裸 appKey 抛异常（因为缺 origin）。扫码流程产出的二维码是配对 URL（含 origin），粘贴 appKey 时由 UI 层结合网关地址字段另行组装（后续 Task 接线处理）。

- [ ] **Step 8: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.*" -q`
Expected: PASS。

- [ ] **Step 9: 加 zxing core 依赖**

`gradle/libs.versions.toml` 增加：
```toml
zxing = "3.5.4"
```
`[libraries]` 增加：
```toml
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
```
`app/build.gradle.kts` dependencies 增加 `implementation(libs.zxing.core)`。

- [ ] **Step 10: 编译确认**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 11: 提交**

```bash
git add app/amadeus-android/gradle/libs.versions.toml app/amadeus-android/app/build.gradle.kts app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing
git commit -m "feat(app): pairing key and gateway origin parsing with zxing dep"
```

---

### Task 2: 纯 JVM 解码层（QrDecoder + NV21 → zxing）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/QrDecoder.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/QrDecoderTest.kt`

**Interfaces:**
- Produces: `object QrDecoder { fun decodeNv21(yPlane: ByteArray, width: Int, height: Int): String? }` — 用 zxing `PlanarYUVLuminanceSource` + `HybridBinarizer` + `MultiFormatReader`（hints: `DecodeHintType.TRY_HARDER=true`, `POSSIBLE_FORMATS=[QR_CODE]`）；失败返回 null。输入尺寸约定：yPlane 长 = width*height（仅亮度平面）。

- [ ] **Step 1: 写失败测试（QrDecoderTest）**

```kotlin
package com.amadeus.whale.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
  @Test fun decodesRenderedPairingLink() {
    val text = "https://192.168.1.20:3444/mobile-access/pair#instance=${"a".repeat(64)}&token=${"A".repeat(43)}"
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256)
    assertEquals(text, QrDecoder.decodeNv21(toGrayscale(matrix, 256), 256, 256))
  }

  @Test fun returnsNullForUnstructuredNoise() {
    val noise = ByteArray(100 * 100) { 128.toByte() }
    assertNull(QrDecoder.decodeNv21(noise, 100, 100))
  }

  @Test fun returnsNullForTruncatedFrame() {
    assertNull(QrDecoder.decodeNv21(ByteArray(100 * 100), 200, 200))
  }

  private fun toGrayscale(matrix: BitMatrix, size: Int): ByteArray {
    val data = ByteArray(size * size)
    for (y in 0 until size) {
      for (x in 0 until size) {
        data[y * size + x] = if (matrix.get(x, y)) 0.toByte() else 0xff.toByte()
      }
    }
    return data
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.QrDecoderTest" -q`
Expected: FAIL。

- [ ] **Step 3: 实现 QrDecoder.kt**

```kotlin
package com.amadeus.whale.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource

object QrDecoder {
  fun decodeNv21(yPlane: ByteArray, width: Int, height: Int): String? {
    if (yPlane.size < width * height) return null
    val source = PlanarYUVLuminanceSource(yPlane, width, height, 0, 0, width, height, false)
    val reader = MultiFormatReader()
    reader.setHints(mapOf(
      DecodeHintType.TRY_HARDER to true,
      DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    ))
    return try {
      val result = reader.decodeWithState(HybridBinarizer(source))
      result.text
    } catch (_: NotFoundException) {
      null
    } finally {
      reader.reset()
    }
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.QrDecoderTest" -q`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/QrDecoder.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/QrDecoderTest.kt
git commit -m "feat(app): zxing NV21 QR decoder for pairing scan"
```

---

### Task 3: TLS 信任与 CA 校验（PinnedTls）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PinnedTls.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/PinnedTlsTest.kt`

**Interfaces:**
- Produces:
  - `object PinnedTls { fun socketFactory(caDer: ByteArray, instanceId: String): SSLSocketFactory }` — 校验 CA 指纹==instanceId（否则抛 `SecurityException`），构建 KeyStore+TrustManagerFactory+SSLContext 的 socketFactory。
  - `fun validateCertificate(caDer: ByteArray, instanceId: String): X509Certificate` — 校验自签名（basicConstraints>=0、subject==issuer、self-verify）+ SHA-256 指纹（hex lowercase 去冒号）==instanceId；通过则返回证书，否则抛 `SecurityException`。
  - `fun sha256Fingerprint(cert: X509Certificate): String` — 64 hex lowercase。

- [ ] **Step 1: 写失败测试（PinnedTlsTest，用 JDK 生成自签 CA）**

```kotlin
package com.amadeus.whale.pairing

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension

class PinnedTlsTest {
  private fun makeSelfSignedCa(): Pair<X509Certificate, String> {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(256, SecureRandom())
    val kp: KeyPair = kpg.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = X509v3CertificateBuilder(
      X500Principal("CN=amadeus-test"),
      BigInteger.ONE,
      Date(now - 1000),
      Date(now + 365L * 24 * 3600 * 1000),
      X500Principal("CN=amadeus-test"),
      kp.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
    val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
    val fingerprint = sha256(cert.encoded)
    return cert to fingerprint
  }

  @Test fun validatesSelfSignedCaFingerprint() {
    val (cert, fp) = makeSelfSignedCa()
    assertEquals(fp, PinnedTls.sha256Fingerprint(cert))
    val validated = PinnedTls.validateCertificate(cert.encoded, fp)
    assertEquals(cert, validated)
  }

  @Test fun rejectsFingerprintMismatch() {
    val (cert, _) = makeSelfSignedCa()
    assertThrows(SecurityException::class.java) {
      PinnedTls.validateCertificate(cert.encoded, "f".repeat(64))
    }
  }

  @Test fun rejectsNonSelfSigned() {
    val kpg = KeyPairGenerator.getInstance("EC"); kpg.initialize(256)
    val kp = kpg.generateKeyPair()
    val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val now = System.currentTimeMillis()
    val builder = X509v3CertificateBuilder(
      X500Principal("CN=issuer"),
      BigInteger.ONE, Date(now - 1000), Date(now + 1000000),
      X500Principal("CN=leaf"), kp.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    val cert = JcaX509CertificateConverter().getCertificate(
      builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(other.private)))
    // 独立签名者（issuer CN != subject CN 且 self-verify 失败）
    assertThrows(SecurityException::class.java) {
      PinnedTls.validateCertificate(cert.encoded, "f".repeat(64))
    }
  }

  private fun sha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
```

注：此测试需要 BouncyCastle（`org.bouncycastle`）。若测试编译报缺依赖，在 `app/build.gradle.kts` 的 `testImplementation` 加：
```toml
bouncycastle = "1.78.1"
```
```toml
bcprov = { group = "org.bouncycastle", name = "bcprov-jdk18on", version.ref = "bouncycastle" }
bcpkix = { group = "org.bouncycastle", name = "bcpkix-jdk18on", version.ref = "bouncycastle" }
```
```kotlin
testImplementation(libs.bcprov)
testImplementation(libs.bcpkix)
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.PinnedTlsTest" -q`
Expected: FAIL（`Unresolved reference: PinnedTls`）。

- [ ] **Step 3: 实现 PinnedTls.kt**

```kotlin
package com.amadeus.whale.pairing

import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object PinnedTls {
  fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
      .joinToString("") { "%02x".format(it) }

  fun validateCertificate(caDer: ByteArray, instanceId: String): X509Certificate {
    val factory = CertificateFactory.getInstance("X.509")
    val cert = factory.generateCertificate(caDer.inputStream()) as X509Certificate
    val basic = cert.basicConstraints
    if (basic < 0) throw SecurityException("not a CA certificate")
    if (cert.subjectX500Principal != cert.issuerX500Principal) throw SecurityException("not self-signed")
    try {
      cert.verify(cert.publicKey)
    } catch (error: Exception) {
      throw SecurityException("self-verification failed", error)
    }
    if (sha256Fingerprint(cert) != instanceId) throw SecurityException("CA fingerprint does not match instance id")
    return cert
  }

  fun socketFactory(caDer: ByteArray, instanceId: String): SSLSocketFactory {
    val cert = validateCertificate(caDer, instanceId)
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("amadeus", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    val context = SSLContext.getInstance("TLS")
    context.init(null, tmf.trustManagers, SecureRandom())
    return context.socketFactory
  }

  fun trustManager(caDer: ByteArray, instanceId: String): X509TrustManager {
    val cert = validateCertificate(caDer, instanceId)
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setCertificateEntry("amadeus", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.PinnedTlsTest" -q`
Expected: PASS（3 tests）。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/PinnedTls.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/PinnedTlsTest.kt app/amadeus-android/gradle/libs.versions.toml app/amadeus-android/app/build.gradle.kts
git commit -m "feat(app): pinned TLS trust and CA fingerprint validation"
```

---

### Task 4: NativeAuthClient（ca.cer / native-pair / native-renew）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/NativeAuthClient.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/NativeAuthClientTest.kt`

**Interfaces:**
- Produces:
  - `data class NativeSession(val instanceId: String, val deviceId: String, val deviceToken: String?, val deviceExpiresAt: Long?, val sessionToken: String, val csrfToken: String, val sessionExpiresAt: Long)`
  - `enum class NativeAuthFailureKind { PAIRING_EXPIRED, DEVICE_LIMIT, RATE_LIMITED, TIMEOUT, TLS, NETWORK, SERVER_UNAVAILABLE, INVALID_RESPONSE }`
  - `class NativeAuthException(val kind: NativeAuthFailureKind, message: String, cause: Throwable? = null) : Exception(message, cause)`
  - `class NativeAuthClient { suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray; suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String? = null): NativeSession; suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession }`
  - `fun parseNativeSessionResponse(json: String, expectedInstanceId: String, keys: Set<String>): NativeSession`

时间戳为 epoch millis。校验规则（spec 3b）：响应键集合精确匹配；instanceId==expected；deviceId 32hex；deviceToken/token 43 base64url；sessionExpiresAt 为未来时间戳；pair 时 deviceExpiresAt>=sessionExpiresAt。

- [ ] **Step 1: 写失败测试（NativeAuthClientTest，MockWebServer）**

```kotlin
package com.amadeus.whale.pairing

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeAuthClientTest {
  private lateinit var server: MockWebServer
  private val now = System.currentTimeMillis()

  @Before fun setUp() { server = MockWebServer(); server.start() }

  @After fun tearDown() { server.shutdown() }

  private fun origin(): GatewayOrigin {
    val url = server.url("/")
    return GatewayOrigin.parse("https://${url.host}:${url.port}")
  }

  private fun pairBody(): String {
    val future = now + 3600_000
    return """{"instanceId":"${"a".repeat(64)}","deviceId":"${"b".repeat(32)}","deviceToken":"${"C".repeat(43)}","deviceExpiresAt":$future,"sessionToken":"${"D".repeat(43)}","csrfToken":"${"e".repeat(43)}","sessionExpiresAt":$future}"""
  }

  @Test fun fetchPairingCaReturnsDerBytes() = runTest {
    server.enqueue(MockResponse.Builder().code(200)
      .addHeader("Content-Type", "application/pkix-cert")
      .body(ByteArray(16) { it.toByte() }).build())
    val ca = NativeAuthClient(OkHttpClient()).fetchPairingCa(origin())
    assertEquals(16, ca.size)
  }

  @Test fun pairParsesValidSession() = runTest {
    server.enqueue(MockResponse.Builder().code(201).body(pairBody()).build())
    val session = NativeAuthClient(OkHttpClient()).pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64))
    assertEquals("a".repeat(64), session.instanceId)
    assertEquals("b".repeat(32), session.deviceId)
    assertEquals("C".repeat(43), session.deviceToken)
  }

  @Test fun pairRejectsInstanceIdMismatch() = runTest {
    server.enqueue(MockResponse.Builder().code(201).body(pairBody()).build())
    assertThrows(NativeAuthException::class.java) {
      runTest { NativeAuthClient(OkHttpClient()).pair(origin(), "T".repeat(43), ByteArray(0), "f".repeat(64)) }
    }
  }

  @Test fun pairMapsHttpErrorsToKinds() = runTest {
    server.enqueue(MockResponse.Builder().code(401).body("{}").build())
    val error = assertThrows(NativeAuthException::class.java) {
      runTest { NativeAuthClient(OkHttpClient()).pair(origin(), "T".repeat(43), ByteArray(0), "a".repeat(64)) }
    }
    assertEquals(NativeAuthFailureKind.PAIRING_EXPIRED, error.kind)
  }
}
```

注：本仓库 okhttp 是 4.12.0，对应 `mockwebserver`（`com.squareup.okhttp3:mockwebserver`，类名 `MockWebServer`/`MockResponse`，非 mockwebserver3）。**确认包名**：项目已用 `libs.okhttp.mockwebserver`（okhttp 4.12.0 的 mockwebserver 是 `okhttp3.mockwebserver.MockWebServer` / `okhttp3.mockwebserver.MockResponse`）。上面写 `mockwebserver3.*` 是错的，请改为 `okhttp3.mockwebserver.MockWebServer` / `okhttp3.mockwebserver.MockResponse`，`MockResponse().setResponseCode(201).setBody(...)` 老 API。参考现有 `AmadeusApiTest` 的实际用法保持一致。

测试断言 `assertThrows` 包住 `runTest` 会因 suspend 无法直接 assertThrows——**改为 `runCatching { ... }.exceptionOrNull()` 断言**（参考现有测试风格），或把 `runCatching` 结果判 kind。请以 `okhttp3.mockwebserver` 真实 API + 现有测试风格落地。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.NativeAuthClientTest" -q`
Expected: FAIL（`Unresolved reference: NativeAuthClient`）。

- [ ] **Step 3: 实现 NativeAuthClient.kt**

```kotlin
package com.amadeus.whale.pairing

import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class NativeSession(
  val instanceId: String,
  val deviceId: String,
  val deviceToken: String?,
  val deviceExpiresAt: Long?,
  val sessionToken: String,
  val csrfToken: String,
  val sessionExpiresAt: Long,
)

enum class NativeAuthFailureKind { PAIRING_EXPIRED, DEVICE_LIMIT, RATE_LIMITED, TIMEOUT, TLS, NETWORK, SERVER_UNAVAILABLE, INVALID_RESPONSE }

class NativeAuthException(val kind: NativeAuthFailureKind, message: String, cause: Throwable? = null) :
  Exception(message, cause)

class NativeAuthClient(private val client: OkHttpClient) {
  private val jsonType = "application/json".toMediaType()

  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("${origin.serialized}/amadeus/ca.cer").get().build()
    val call = client.newCall(request)
    try {
      call.execute().use { resp ->
        if (resp.code != 200) throw NativeAuthException(
          NativeAuthFailureKind.SERVER_UNAVAILABLE, "ca.cer returned ${resp.code}")
        val bytes = resp.body?.bytes().orEmpty()
        if (bytes.size > 16 * 1024) throw NativeAuthException(NativeAuthFailureKind.INVALID_RESPONSE, "ca.cer too large")
        // 仅检查可解析为 X.509；错误抛 TLS/INVALID_RESPONSE
        try {
          CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
        } catch (error: Exception) {
          throw NativeAuthException(NativeAuthFailureKind.INVALID_RESPONSE, "ca.cer is not X.509", error)
        }
        bytes
      }
    } catch (error: NativeAuthException) {
      throw error
    } catch (error: javax.net.ssl.SSLException) {
      throw NativeAuthException(NativeAuthFailureKind.TLS, "tls failure: ${error.message}", error)
    } catch (error: java.net.ConnectException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "unreachable: ${error.message}", error)
    } catch (error: java.net.SocketTimeoutException) {
      throw NativeAuthException(NativeAuthFailureKind.TIMEOUT, "timeout: ${error.message}", error)
    } catch (error: java.io.IOException) {
      throw NativeAuthException(NativeAuthFailureKind.NETWORK, "io: ${error.message}", error)
    }
  }

  suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String? = null): NativeSession =
    withContext(Dispatchers.IO) {
      val payload = JSONObject().put("token", token)
      if (label != null) payload.put("label", label)
      val request = Request.Builder()
        .url("${origin.serialized}/amadeus/auth/native-pair")
        .post(payload.toString().toRequestBody(jsonType)).build()
      postForSession(request, instanceId, PAIR_KEYS, pair = true)
    }

  suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession =
    withContext(Dispatchers.IO) {
      val request = Request.Builder()
        .url("${origin.serialized}/amadeus/auth/native-renew")
        .post(JSONObject().put("deviceToken", deviceToken).toString().toRequestBody(jsonType)).build()
      postForSession(request, instanceId, RENEW_KEYS, pair = false)
    }

  private suspend fun postForSession(request: Request, expectedInstanceId: String, keys: Set<String>, pair: Boolean): NativeSession {
    val client = ... // 注意：此 client 应是已注入 PinnedTls 的认证 client（由外部传入）
    // 实际实现：caDer 注入 sslSocketFactory 后用本 client 发请求
    ...
  }
}
```

**关键修正**：`NativeAuthClient` 的 `pair`/`renew` 需要 **PinnedTls 的 TLS client**（`caDer` 必须参与构建）。因此 `NativeAuthClient` 构造应接收一个 `clientFactory: (ByteArray, String) -> OkHttpClient`（注入 CA 后构造认证 client），而不是裸 client。请按下述最终签名实现：

```kotlin
class NativeAuthClient(
  private val bootstrapClient: OkHttpClient,
  private val sessionClientFactory: (caDer: ByteArray, instanceId: String) -> OkHttpClient,
)
```

- `fetchPairingCa` 用 `bootstrapClient`（trust-all，见 Step 4 bootstrapClient 构造）。
- `pair`/`renew` 用 `sessionClientFactory(caDer, instanceId)` 构造的 client 发请求（TLS 已 pin 到 CA，响应校验照常）。
- 错误映射：401/403 → `PAIRING_EXPIRED`；409 → `DEVICE_LIMIT`；429 → `RATE_LIMITED`；5xx → `SERVER_UNAVAILABLE`；SSL 异常 → `TLS`；ConnectException → `NETWORK`；SocketTimeoutException → `TIMEOUT`；解析/键集合不符 → `INVALID_RESPONSE`。
- `parseNativeSessionResponse(json, expectedInstanceId, keys, pair)` 校验键集合精确匹配、instanceId==expected、deviceId 32hex、deviceToken/token 43 base64url、sessionExpiresAt 未来、pair 时 deviceExpiresAt>=sessionExpiresAt。

- [ ] **Step 4: bootstrap trust-all client 实现**

```kotlin
// 仅供 bootstrap 端点（ca.cer/discovery/metadata）。信任所有证书，仅一次取 CA 用。
fun trustAllClient(): OkHttpClient {
  val tmf = arrayOf<TrustManager>(object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
  })
  val context = SSLContext.getInstance("TLS")
  context.init(null, tmf, SecureRandom())
  return OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .sslSocketFactory(context.socketFactory, tmf[0] as X509TrustManager)
    .build()
}
```

- [ ] **Step 5: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.NativeAuthClientTest" -q`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/NativeAuthClient.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/NativeAuthClientTest.kt
git commit -m "feat(app): native auth client for ca.cer, pair and renew"
```

---

### Task 5: 认证 HTTP 层（AuthCookieJar + AuthInterceptor + AuthHttpClient）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AuthCookieJar.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AuthInterceptor.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/AuthHttpClientTest.kt`

**Interfaces:**
- Produces:
  - `class AuthCookieJar : CookieJar` — 内存保存 `amw_session`/`amw_csrf`；`saveFromResponse` 解析 cookie；`loadForRequest` 回填。暴露 `fun sessionCookie(): String?`、`fun csrfCookie(): String?`、`fun clear()`。
  - `class AuthInterceptor(private val cookieJar: AuthCookieJar) : Interceptor` — 非 GET/HEAD 自动加 `x-amw-csrf` 头（值取 csrfCookie，无则跳过）。
  - `fun buildAuthClient(base: OkHttpClient, cookieJar: AuthCookieJar): OkHttpClient` — `base.newBuilder().cookieJar(cookieJar).addInterceptor(AuthInterceptor(cookieJar)).build()`。

- [ ] **Step 1: 写失败测试（AuthHttpClientTest）**

```kotlin
package com.amadeus.whale.pairing

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthHttpClientTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() { server = MockWebServer(); server.start() }

  @After fun tearDown() { server.shutdown() }

  private fun baseUrl() = server.url("/").toString().trimEnd('/')

  @Test fun storesSessionAndCsrfCookies() {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure; HttpOnly", "amw_csrf=csrf99; Path=/; Secure")
    assertEquals("abc123", jar.sessionCookie())
    assertEquals("csrf99", jar.csrfCookie())
  }

  @Test fun postAddsCsrfHeader() = runTest {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure", "amw_csrf=csrf99; Path=/; Secure")
    val client = buildAuthClient(OkHttpClient(), jar)
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    client.newCall(Request.Builder().url(baseUrl()).post("{}".toRequestBody("application/json".toMediaType())).build())
      .execute().use { }
    val recorded = server.takeRequest()
    assertEquals("csrf99", recorded.getHeader("x-amw-csrf"))
    assertTrue(recorded.getHeader("Cookie")?.contains("amw_session=abc123") == true)
  }

  @Test fun getDoesNotAddCsrfHeader() = runTest {
    val jar = AuthCookieJar()
    jar.store("amw_session=abc123; Path=/; Secure", "amw_csrf=csrf99; Path=/; Secure")
    val client = buildAuthClient(OkHttpClient(), jar)
    server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
    client.newCall(Request.Builder().url(baseUrl()).get().build()).execute().use { }
    val recorded = server.takeRequest()
    assertNull(recorded.getHeader("x-amw-csrf"))
  }

  @Test fun clearRemovesCookies() {
    val jar = AuthCookieJar()
    jar.store("amw_session=a; Path=/", "amw_csrf=b; Path=/")
    jar.clear()
    assertNull(jar.sessionCookie())
    assertNull(jar.csrfCookie())
  }
}
```

注：`okhttp3.mockwebserver` 的 `MockResponse` 用 `setResponseCode`/`setBody`；`server.url("/")` 返回 HttpUrl。现有测试确认实际 API。若 `AuthCookieJar` 实现 `CookieJar`（okhttp3.CookieJar），`saveFromResponse` 用 `Cookie.parseAll(url, headers)`；`loadForRequest` 需构造 `List<Cookie>`。测试里直接调 jar 的辅助方法 `store(sessionHeader, csrfHeader)`（自定义），与 CookieJar 接口并存。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.AuthHttpClientTest" -q`
Expected: FAIL。

- [ ] **Step 3: 实现 AuthCookieJar.kt + AuthInterceptor.kt**

```kotlin
package com.amadeus.whale.pairing

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Headers

class AuthCookieJar : CookieJar {
  private var session: String? = null
  private var csrf: String? = null

  fun store(sessionHeader: String, csrfHeader: String) {
    val sessionName = "amw_session="
    val csrfName = "amw_csrf="
    session = sessionHeader.substringAfter(sessionName, "").substringBefore(";").takeIf { it.isNotEmpty() }
    csrf = csrfHeader.substringAfter(csrfName, "").substringBefore(";").takeIf { it.isNotEmpty() }
  }

  fun sessionCookie(): String? = session
  fun csrfCookie(): String? = csrf

  fun clear() { session = null; csrf = null }

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    for (cookie in cookies) {
      when (cookie.name) {
        "amw_session" -> session = cookie.value
        "amw_csrf" -> csrf = cookie.value
      }
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val result = mutableListOf<Cookie>()
    session?.let { result.add(Cookie.Builder().name("amw_session").value(it).domain(url.host).build()) }
    csrf?.let { result.add(Cookie.Builder().name("amw_csrf").value(it).domain(url.host).build()) }
    return result
  }
}
```

```kotlin
package com.amadeus.whale.pairing

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val cookieJar: AuthCookieJar) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val builder = request.newBuilder()
    if (request.method != "GET" && request.method != "HEAD") {
      cookieJar.csrfCookie()?.let { builder.header("x-amw-csrf", it) }
    }
    return chain.proceed(builder.build())
  }
}
```

```kotlin
package com.amadeus.whale.pairing

import okhttp3.OkHttpClient

fun buildAuthClient(base: OkHttpClient, cookieJar: AuthCookieJar): OkHttpClient =
  base.newBuilder()
    .cookieJar(cookieJar)
    .addInterceptor(AuthInterceptor(cookieJar))
    .build()
```

注：`AuthCookieJar` 主要靠 App 显式 `store(...)`（native-pair 直接拿到 sessionToken/csrfToken），`CookieJar` 接口实现留作 okhttp 自动路径兜底。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.AuthHttpClientTest" -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AuthCookieJar.kt app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AuthInterceptor.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/AuthHttpClientTest.kt
git commit -m "feat(app): auth cookie jar and csrf interceptor"
```

---

### Task 6: DeviceCredentialStore（Keystore AES-GCM 加密）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/DeviceCredentialStore.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/DeviceCredentialStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class DeviceCredential(val instanceId: String, val deviceToken: String, val deviceExpiresAt: Long, val caCertificate: ByteArray, val origin: String)`（caCertificate = DER base64 编码存储）
  - `interface DeviceCredentialStore { fun save(origin: String, credential: DeviceCredential): Boolean; fun load(origin: String): DeviceCredential?; fun clear(origin: String) }`
  - `class KeystoreDeviceCredentialStore(private val prefs: SharedPreferences, private val crypto: AesGcmCrypto = KeystoreAesGcmCrypto(KEY_ALIAS)) : DeviceCredentialStore` — 加密后存 SharedPreferences，key 名 `credential_<origin>`。
  - `class KeystoreAesGcmCrypto(private val alias: String)` — `encrypt(plaintext): ByteArray` / `decrypt(ciphertext): ByteArray`（AES/GCM，Keystore 非导出 key）。

测试策略：Keystore AES-GCM 依赖 Android Keystore（JVM 单测不可用）。为可单测，注入一个 `AesGcmCrypto` 接口的 fake（内存 XOR/AES 由 javax.crypto 生成临时 key）来测 `KeystoreDeviceCredentialStore` 的序列化/存取逻辑。真机 Keystore 实现仅做薄封装。

- [ ] **Step 1: 写失败测试（DeviceCredentialStoreTest，用 FakeAesGcm）**

```kotlin
package com.amadeus.whale.pairing

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCredentialStoreTest {
  private class FakeAesGcm : AesGcmCrypto {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plaintext: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
      cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
      return iv + cipher.doFinal(plaintext)
    }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
      val iv = ciphertext.copyOfRange(0, 12)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
      return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
    }
  }

  @Test fun roundtripsCredentialPerOrigin() {
    val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      .getSharedPreferences("test_device", android.content.Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    val cred = DeviceCredential("a".repeat(64), "C".repeat(43), 123456789L, ByteArray(16) { 1 }, "https://h:3444")
    store.save("https://h:3444", cred)
    val loaded = store.load("https://h:3444")
    assertNotNull(loaded)
    assertEquals(cred.instanceId, loaded!!.instanceId)
    assertEquals(cred.deviceToken, loaded.deviceToken)
    assertEquals(cred.deviceExpiresAt, loaded.deviceExpiresAt)
    assertEquals(cred.origin, loaded.origin)
    assertEquals(cred.caCertificate.toList(), loaded.caCertificate.toList())
  }

  @Test fun loadMissingOriginReturnsNull() {
    val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      .getSharedPreferences("test_device_missing", android.content.Context.MODE_PRIVATE)
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    assertNull(store.load("https://missing:1"))
  }

  @Test fun clearRemovesCredential() {
    val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      .getSharedPreferences("test_device_clear", android.content.Context.MODE_PRIVATE)
    prefs.edit().clear().commit()
    val store = KeystoreDeviceCredentialStore(prefs, FakeAesGcm())
    store.save("https://h:3444", DeviceCredential("a".repeat(64), "C".repeat(43), 123456789L, ByteArray(1), "https://h:3444"))
    store.clear("https://h:3444")
    assertNull(store.load("https://h:3444"))
  }
}
```

注：JVM 单测里没有 `androidx.test.core.app.ApplicationProvider`（那是 instrumented test 依赖）。**改用注入 PrefsStore 抽象**（仓库已有 `com.amadeus.whale.PrefsStore`/`SharedPrefsStore`），用内存 fake 实现测 store 逻辑。`KeystoreDeviceCredentialStore` 构造收 `PrefsStore`（而非 SharedPreferences）。这样纯 JVM 可测。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.DeviceCredentialStoreTest" -q`
Expected: FAIL。

- [ ] **Step 3: 实现 DeviceCredentialStore.kt**

```kotlin
package com.amadeus.whale.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.amadeus.whale.PrefsStore
import java.security.KeyStore

data class DeviceCredential(
  val instanceId: String,
  val deviceToken: String,
  val deviceExpiresAt: Long,
  val caCertificate: ByteArray,
  val origin: String,
)

interface AesGcmCrypto {
  fun encrypt(plaintext: ByteArray): ByteArray
  fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Android Keystore AES/GCM; key never leaves the secure element. */
class KeystoreAesGcmCrypto(private val alias: String) : AesGcmCrypto {
  override fun encrypt(plaintext: ByteArray): ByteArray = crypt(plaintext, Cipher.ENCRYPT_MODE)
  override fun decrypt(ciphertext: ByteArray): ByteArray = crypt(ciphertext, Cipher.DECRYPT_MODE)

  private fun crypt(input: ByteArray, mode: Int): ByteArray {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!ks.containsAlias(alias)) {
      val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
      generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build())
      generator.generateKey()
    }
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    if (mode == Cipher.ENCRYPT_MODE) {
      cipher.init(Cipher.ENCRYPT_MODE, ks.getKey(alias, null))
      val iv = cipher.iv
      return iv + cipher.doFinal(input)
    }
    val iv = input.copyOfRange(0, 12)
    val body = input.copyOfRange(12, input.size)
    cipher.init(Cipher.DECRYPT_MODE, ks.getKey(alias, null), javax.crypto.spec.GCMParameterSpec(128, iv))
    return cipher.doFinal(body)
  }
}

interface DeviceCredentialStore {
  fun save(origin: String, credential: DeviceCredential): Boolean
  fun load(origin: String): DeviceCredential?
  fun clear(origin: String)
}

class KeystoreDeviceCredentialStore(
  private val prefs: PrefsStore,
  private val crypto: AesGcmCrypto = KeystoreAesGcmCrypto("amw_device_v1"),
) : DeviceCredentialStore {
  private fun key(origin: String) = "credential_$origin"

  override fun save(origin: String, credential: DeviceCredential): Boolean {
    val record = JSONObject()
      .put("instanceId", credential.instanceId)
      .put("deviceToken", credential.deviceToken)
      .put("deviceExpiresAt", credential.deviceExpiresAt)
      .put("caCertificate", Base64.encodeToString(credential.caCertificate, Base64.NO_WRAP))
      .put("origin", credential.origin)
    val encrypted = Base64.encodeToString(crypto.encrypt(record.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    prefs.putString(key(origin), encrypted)
    return true
  }

  override fun load(origin: String): DeviceCredential? {
    val encrypted = prefs.getString(key(origin)) ?: return null
    return try {
      val json = JSONObject(String(crypto.decrypt(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
      DeviceCredential(
        instanceId = json.getString("instanceId"),
        deviceToken = json.getString("deviceToken"),
        deviceExpiresAt = json.getLong("deviceExpiresAt"),
        caCertificate = Base64.decode(json.getString("caCertificate"), Base64.NO_WRAP),
        origin = json.getString("origin"),
      )
    } catch (_: Exception) {
      null
    }
  }

  override fun clear(origin: String) { prefs.remove(key(origin)) }
}
```

注：`crypt` 里 `Cipher.ENCRYPT_MODE` 用了 `javax.crypto.Cipher` 常量的名称冲突——请在文件顶部 `import javax.crypto.Cipher`，并统一用 `Cipher.ENCRYPT_MODE`/`Cipher.DECRYPT_MODE`。`JSONObject` 用 `org.json`（Android 自带，JVM 单测可用）。测试注入 FakeAesGcm + 内存 PrefsStore fake（参考仓库 `AmadeusPrefsTest` 的 FakeStore）。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.DeviceCredentialStoreTest" -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/DeviceCredentialStore.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/DeviceCredentialStoreTest.kt
git commit -m "feat(app): keystore-encrypted device credential store"
```

---

### Task 7: 编排层 AmadeusAuthClient

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AmadeusAuthClient.kt`
- Test: `app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/AmadeusAuthClientTest.kt`

**Interfaces:**
- Produces:
  - `class AmadeusAuthClient(private val nativeAuth: NativeAuthClient, private val credentialStore: DeviceCredentialStore, private val baseClient: OkHttpClient)`：
    - `suspend fun pair(target: PairingScanTarget, label: String? = null): AuthResult` — fetchPairingCa → validate → native pair → store credential → build auth OkHttpClient → 返回。
    - `suspend fun restore(origin: GatewayOrigin): AuthResult` — load credential → 过期检查 → renew → 成功返回认证 client + 更新 store；失败清理。
    - `fun createSessionClient(origin: GatewayOrigin): OkHttpClient?` — 有 credential 则 build 认证 client（cookie+CSRF+TLS pinning）。
    - `fun clear(origin: GatewayOrigin)`。
  - `sealed class AuthResult { data class Success(val origin: GatewayOrigin, val instanceId: String, val deviceId: String, val client: OkHttpClient, val sessionExpiresAt: Long) : AuthResult(); data class Failure(val kind: NativeAuthFailureKind, val message: String, val cause: Throwable? = null) : AuthResult() }`

- [ ] **Step 1: 写失败测试（AmadeusAuthClientTest，MockWebServer 模拟 pair + ca.cer）**

```kotlin
package com.amadeus.whale.pairing

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AmadeusAuthClientTest {
  private lateinit var server: MockWebServer
  private val now = System.currentTimeMillis()

  @Before fun setUp() { server = MockWebServer(); server.start() }

  @After fun tearDown() { server.shutdown() }

  private fun origin(): GatewayOrigin {
    val url = server.url("/")
    return GatewayOrigin.parse("https://${url.host}:${url.port}")
  }

  private fun target(): PairingScanTarget = PairingScanTarget(origin(), "a".repeat(64), "T".repeat(43))

  private fun caDer(): ByteArray {
    // 注意：pair 流程会校验 CA 指纹。用注入 fake 的方式让 NativeAuthClient 跳过真实 TLS；
    // 本测试聚焦编排（fetch→pair→store→client 构建）。可用一个可控的 NativeAuthClient 假实现注入。
    return ByteArray(0)
  }

  @Test fun pairStoresCredentialAndReturnsClient() = runTest {
    // 构造 fake NativeAuthClient（不真正连 TLS），记录调用，返回固定 session
    val fake = FakeNativeAuthClient()
    val store = FakeCredentialStore()
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.pair(target())
    assertTrue(result is AuthResult.Success)
    val success = result as AuthResult.Success
    assertEquals("a".repeat(64), success.instanceId)
    assertNotNull(store.load(origin().serialized))
  }

  @Test fun restoreRenewsExistingCredential() = runTest {
    val fake = FakeNativeAuthClient()
    val store = FakeCredentialStore()
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now + 3600_000, ByteArray(0), origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.restore(origin())
    assertTrue(result is AuthResult.Success)
  }

  @Test fun restoreClearsOnFailure() = runTest {
    val fake = FakeNativeAuthClient(renewFails = true)
    val store = FakeCredentialStore()
    store.save(origin().serialized, DeviceCredential("a".repeat(64), "C".repeat(43), now + 3600_000, ByteArray(0), origin().serialized))
    val auth = AmadeusAuthClient(fake, store, OkHttpClient())
    val result = auth.restore(origin())
    assertTrue(result is AuthResult.Failure)
    assertNull(store.load(origin().serialized))
  }
}

// 测试内 fake 实现（文件内私有）
private class FakeNativeAuthClient(
  var renewFails: Boolean = false,
) {
  var paired = false
  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray = ByteArray(0)
  suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String?): NativeSession {
    paired = true
    val future = System.currentTimeMillis() + 3600_000
    return NativeSession(instanceId, "b".repeat(32), "C".repeat(43), future, "D".repeat(43), "e".repeat(43), future)
  }
  suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession {
    if (renewFails) throw NativeAuthException(NativeAuthFailureKind.PAIRING_EXPIRED, "expired")
    val future = System.currentTimeMillis() + 3600_000
    return NativeSession(instanceId, "b".repeat(32), "C".repeat(43), future, "D".repeat(43), "e".repeat(43), future)
  }
}

private class FakeCredentialStore : DeviceCredentialStore {
  private val map = mutableMapOf<String, DeviceCredential>()
  override fun save(origin: String, credential: DeviceCredential): Boolean { map[origin] = credential; return true }
  override fun load(origin: String): DeviceCredential? = map[origin]
  override fun clear(origin: String) { map.remove(origin) }
}
```

注：`AmadeusAuthClient` 构造收 `NativeAuthClient`，但测试想注入 fake。**修正设计**：`AmadeusAuthClient` 依赖抽象接口而非具体类：

```kotlin
interface NativeAuthGateway {
  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray
  suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String? = null): NativeSession
  suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession
}
class NativeAuthClient(...) : NativeAuthGateway { ... }
```

`AmadeusAuthClient(nativeAuth: NativeAuthGateway, credentialStore: DeviceCredentialStore, baseClient: OkHttpClient)`。测试注入 fake `NativeAuthGateway`。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.AmadeusAuthClientTest" -q`
Expected: FAIL。

- [ ] **Step 3: 实现 AmadeusAuthClient.kt**

```kotlin
package com.amadeus.whale.pairing

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

interface NativeAuthGateway {
  suspend fun fetchPairingCa(origin: GatewayOrigin): ByteArray
  suspend fun pair(origin: GatewayOrigin, token: String, caDer: ByteArray, instanceId: String, label: String? = null): NativeSession
  suspend fun renew(origin: GatewayOrigin, deviceToken: String, caDer: ByteArray, instanceId: String): NativeSession
}

sealed class AuthResult {
  data class Success(
    val origin: GatewayOrigin,
    val instanceId: String,
    val deviceId: String,
    val client: OkHttpClient,
    val sessionExpiresAt: Long,
  ) : AuthResult()

  data class Failure(val kind: NativeAuthFailureKind, val message: String, val cause: Throwable? = null) : AuthResult()
}

class AmadeusAuthClient(
  private val nativeAuth: NativeAuthGateway,
  private val credentialStore: DeviceCredentialStore,
  private val baseClient: OkHttpClient,
) {
  suspend fun pair(target: PairingScanTarget, label: String? = null): AuthResult {
    return try {
      val caDer = nativeAuth.fetchPairingCa(target.origin)
      val session = nativeAuth.pair(target.origin, target.token, caDer, target.instanceId, label)
      val credential = DeviceCredential(
        instanceId = session.instanceId,
        deviceToken = session.deviceToken!!,
        deviceExpiresAt = session.deviceExpiresAt!!,
        caCertificate = caDer,
        origin = target.origin.serialized,
      )
      credentialStore.save(target.origin.serialized, credential)
      AuthResult.Success(
        origin = target.origin,
        instanceId = session.instanceId,
        deviceId = session.deviceId,
        client = buildSessionClient(target.origin, session.sessionToken, session.csrfToken, caDer, session.instanceId),
        sessionExpiresAt = session.sessionExpiresAt,
      )
    } catch (error: NativeAuthException) {
      AuthResult.Failure(error.kind, error.message ?: "pair failed", error)
    } catch (error: SecurityException) {
      AuthResult.Failure(NativeAuthFailureKind.TLS, error.message ?: "tls validation failed", error)
    }
  }

  suspend fun restore(origin: GatewayOrigin): AuthResult {
    val credential = credentialStore.load(origin.serialized)
    if (credential == null) return AuthResult.Failure(NativeAuthFailureKind.NETWORK, "no saved credential")
    return try {
      val session = nativeAuth.renew(origin, credential.deviceToken, credential.caCertificate, credential.instanceId)
      credentialStore.save(origin.serialized, credential.copy(
        deviceExpiresAt = session.deviceExpiresAt ?: credential.deviceExpiresAt,
        caCertificate = credential.caCertificate,
      ))
      AuthResult.Success(
        origin = origin,
        instanceId = session.instanceId,
        deviceId = session.deviceId,
        client = buildSessionClient(origin, session.sessionToken, session.csrfToken, credential.caCertificate, session.instanceId),
        sessionExpiresAt = session.sessionExpiresAt,
      )
    } catch (error: NativeAuthException) {
      credentialStore.clear(origin.serialized)
      AuthResult.Failure(error.kind, error.message ?: "renew failed", error)
    }
  }

  fun createSessionClient(origin: GatewayOrigin): OkHttpClient? {
    val credential = credentialStore.load(origin.serialized) ?: return null
    val jar = AuthCookieJar()
    // session token 不可从 credential 单独恢复——需 renew 获取。这里仅当内存有 session 才有意义；
    // 冷启动路径走 restore()。此方法供 pair/restore 后复用已有 session。
    return null
  }

  fun buildSessionClient(origin: GatewayOrigin, sessionToken: String, csrfToken: String, caDer: ByteArray, instanceId: String): OkHttpClient {
    val jar = AuthCookieJar()
    jar.store("amw_session=$sessionToken; Path=/; Secure; HttpOnly", "amw_csrf=$csrfToken; Path=/; Secure")
    val pinned = baseClient.newBuilder()
      .sslSocketFactory(PinnedTls.socketFactory(caDer, instanceId), PinnedTls.trustManager(caDer, instanceId))
      .build()
    return buildAuthClient(pinned, jar)
  }

  fun clear(origin: GatewayOrigin) { credentialStore.clear(origin.serialized) }
}
```

注：`createSessionClient` 因 credential 只存 deviceToken（不含 sessionToken/csrf），冷启动必须 renew 拿新 session；因此**移除** `createSessionClient`，统一由 `restore()`/`pair()` 返回带 session 的认证 client。AppRoot 只保存一次 `AuthResult.Success.client` 复用。若 session 过期（业务 401），调 `restore()` 换新 client。

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.pairing.AmadeusAuthClientTest" -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AmadeusAuthClient.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/pairing/AmadeusAuthClientTest.kt app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/NativeAuthClient.kt
git commit -m "feat(app): amadeus auth orchestration client"
```

---

### Task 8: ScanActivity（相机扫码）+ Manifest

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/scan/ScanActivity.kt`
- Modify: `app/amadeus-android/app/src/main/AndroidManifest.xml`
- Modify: `app/amadeus-android/app/src/main/res/values/strings.xml`（新增扫码提示文案，若存在）

**Interfaces:**
- Produces:
  - `class ScanActivity : Activity`（传统 View，全屏，黑底）— legacy Camera.open() + SurfaceHolder；预览回调收到 NV21 帧 → `QrDecoder.decodeNv21` → 成功 `setResult(RESULT_OK, Intent().putExtra("pairing_text", text))` + `finish()`；白框取景（FrameLayout 覆层 260dp 边框 View），提示文案"将二维码对准方框"，关闭按钮。**不得**在 Activity 持有 Compose 依赖。
  - Manifest：`<uses-permission android:name="android.permission.CAMERA"/>`、`<uses-feature android:name="android.hardware.camera" android:required="false"/>`、`<activity android:name=".scan.ScanActivity" android:exported="false" android:screenOrientation="portrait"/>`。
  - 运行时权限在启动 ScanActivity 前于调用方（后续 Settings 接线）处理；ScanActivity 自身做防御：无权限 finish 并 RESULT_CANCELED。

- [ ] **Step 1: 写 ScanActivity 实现**

```kotlin
package com.amadeus.whale.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.amadeus.whale.R
import com.amadeus.whale.pairing.QrDecoder

@Suppress("DEPRECATION")
class ScanActivity : Activity(), Camera.PreviewCallback {
  private var camera: Camera? = null
  private var surfaceHolder: SurfaceHolder? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    setContentView(buildUi())
  }

  private fun buildUi(): FrameLayout {
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val preview = SurfaceView(this).apply {
      layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    root.addView(preview)
    surfaceHolder = preview.holder
    surfaceHolder?.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        openCamera(holder)
      }
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
      override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }
    })

    // 取景白框
    val box = ViewGroup(this)
    val frame = FrameLayout(this)
    frame.setBackgroundResource(R.drawable.scan_frame)
    frame.layoutParams = FrameLayout.LayoutParams(260.dp(), 260.dp(), Gravity.CENTER)
    root.addView(frame)

    val hint = TextView(this).apply {
      text = "将二维码对准方框"
      setTextColor(Color.WHITE)
      textSize = 16f
    }
    hint.layoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 90.dp() }
    root.addView(hint)

    val close = Button(this).apply {
      text = "关闭"
      setOnClickListener { setResult(RESULT_CANCELED); finish() }
    }
    close.layoutParams = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = 40.dp() }
    root.addView(close)
    return root
  }

  @Suppress("DEPRECATION")
  private fun openCamera(holder: SurfaceHolder) {
    val cam = try { Camera.open() } catch (error: Exception) { null }
    cam?.let {
      camera = it
      it.setPreviewDisplay(holder)
      val params = it.parameters
      val size = params.supportedPreviewSizes?.minByOrNull { p -> Math.abs(p.width - 720) }
      if (size != null) { params.setPreviewSize(size.width, size.height) }
      it.parameters = params
      it.setPreviewCallback(this)
      it.startPreview()
    }
  }

  private fun releaseCamera() {
    camera?.let {
      it.setPreviewCallback(null)
      it.stopPreview()
      it.release()
    }
    camera = null
  }

  @Suppress("DEPRECATION")
  override fun onPreviewFrame(data: ByteArray, camera: Camera) {
    val size = camera.parameters.previewSize
    val text = QrDecoder.decodeNv21(data, size.width, size.height)
    if (text != null) {
      releaseCamera()
      setResult(RESULT_OK, Intent().putExtra("pairing_text", text))
      finish()
    }
  }

  override fun onDestroy() { releaseCamera(); super.onDestroy() }

  private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
```

注：`R.drawable.scan_frame` 需要在 `res/drawable/scan_frame.xml` 定义（白框 shape，stroke 4dp 白）。请创建该 drawable：
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <stroke android:width="4dp" android:color="#FFFFFF"/>
  <solid android:color="#00000000"/>
</shape>
```
Camera API 已 deprecated 但 spec 明确 legacy Camera API。若编译报 `Camera` 未定义（compileSdk 36 已移除部分旧 API？不会，Camera 仍在），确认导入 `android.hardware.Camera`。

- [ ] **Step 2: Manifest 加权限与 Activity**

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
...
<activity android:name=".scan.ScanActivity" android:exported="false" android:screenOrientation="portrait" />
```

- [ ] **Step 3: 编译确认**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/scan app/amadeus-android/app/src/main/AndroidManifest.xml app/amadeus-android/app/src/main/res/drawable/scan_frame.xml
git commit -m "feat(app): camera scan activity for pairing QR"
```

---

### Task 9: Settings 配对 UI + AppRoot 认证接线

**Files:**
- Modify: `app/amadeus-android/app/src/main/java/com/amadeus/whale/AppRoot.kt`
- Modify: `app/amadeus-android/app/src/main/java/com/amadeus/whale/settings/SettingsViewModel.kt`
- Modify: `app/amadeus-android/app/src/main/java/com/amadeus/whale/settings/SettingsScreen.kt`
- Modify: `app/amadeus-android/app/src/main/java/com/amadeus/whale/MainActivity.kt`
- Modify: `app/amadeus-android/app/src/test/java/com/amadeus/whale/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `AmadeusAuthClient`（Task 7）、`AuthResult`、`GatewayOrigin`、`PairingKey`/`PairingScanTarget`、`ScanActivity`（result extra `"pairing_text"`）、现有 `AmadeusApi`/`AmadeusStream`。
- Produces:
  - `SettingsViewModel` 新增：
    - `var pairingStatus: StateFlow<String>`（"未配对" / "已配对 · deviceId 后 8 位" / 错误文案）。
    - `suspend fun pairWithKey(keyInput: String): Boolean` — 解析 `PairingScanTarget`（appKey 缺 origin 时报错提示"需配对 URL 或扫码"）→ 走 `AmadeusAuthClient.pair` → 成功存 `prefs.baseUrl` + 会话 client → `_status="已连接"`；失败 `_status="配对失败: <kind>/<message>"`。
    - `fun registerScanResult(text: String)` — 设置页从 `ScanActivity` 返回后调用，解析并 `pairWithKey(text)`。
    - 依赖注入：`AmadeusAuthClient` + `DeviceCredentialStore`。
  - `SettingsScreen` 新增：网关地址字段下方「配对」区 —— 「扫码」按钮（launchActivityForResult 打开 ScanActivity）+ 粘贴 appKey/URL 文本字段 + 「配对」按钮 + 配对状态文本；断开连接按钮保留。
  - `AppRoot`：
    - `authClient`（remember 构造，含 credentialStore 从 context 建、NativeAuthClient 从 trustAllClient 建）。
    - 启动 `LaunchedEffect(Unit)`：有 baseUrl → `authClient.restore(origin)` → Success→ `sessionClient=success.client` + `screen=Real`；Failure→ `prefs.clear()` + Demo。
    - Real 分支：`api`/`stream` 用 `sessionClient` 构造（`AmadeusApi(url, sessionClient)` / `AmadeusStream(url, sessionClient)`）；保存 `sessionClient` 在 `rememberSaveable` 或 `remember`（restore 成功后）。
    - `SettingsScreen` 传 `authClient` 与扫码回调；`onDone` 重新判断（配对成功→Real）。

- [ ] **Step 1: 扩展 SettingsViewModel 测试（先写失败测试）**

在 `SettingsViewModelTest.kt` 增加用例：
```kotlin
@Test fun pairWithKeyParsesAndStores() = runTest {
  // 注入 fake AmadeusAuthClient（返回 Success），断言 prefs.baseUrl 被设置、status 更新
}
@Test fun pairWithBareKeyRejectsWithHint() = runTest {
  // appKey（无 origin）→ 返回 false + status 含"配对 URL"
}
```

参考现有 SettingsViewModelTest 的 MockWebServer 模式。`AmadeusAuthClient` 测试注入需要 fake——为可注入，`SettingsViewModel` 构造收 `(GatewayOrigin, String) -> AuthResult` 的 `pairer` lambda 或抽象 `PairingService` 接口。请按可控依赖设计：

```kotlin
interface PairingService {
  suspend fun pair(keyInput: String): AuthResult  // 解析+配对，失败返回 Failure
  suspend fun restore(origin: GatewayOrigin): AuthResult
}
class AmadeusPairingService(...) : PairingService  // 组合 AmadeusAuthClient + PairingScanTarget.parse
```

`SettingsViewModel(prefs, apiProvider, pairing: PairingService)`。测试注入 fake PairingService。

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.settings.SettingsViewModelTest" -q`
Expected: FAIL（构造参数变化）。

- [ ] **Step 3: 实现 PairingService + 改造 SettingsViewModel**

创建 `app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AmadeusPairingService.kt`：
```kotlin
package com.amadeus.whale.pairing

class AmadeusPairingService(
  private val authClient: AmadeusAuthClient,
) : PairingService {
  override suspend fun pair(keyInput: String): AuthResult {
    val target = try {
      PairingScanTarget.parse(keyInput)
    } catch (error: IllegalArgumentException) {
      return AuthResult.Failure(NativeAuthFailureKind.INVALID_RESPONSE, error.message ?: "无法解析配对密钥；请使用带网关地址的配对 URL 或扫码")
    }
    return authClient.pair(target)
  }

  override suspend fun restore(origin: GatewayOrigin): AuthResult = authClient.restore(origin)
}

interface PairingService {
  suspend fun pair(keyInput: String): AuthResult
  suspend fun restore(origin: GatewayOrigin): AuthResult
}
```

SettingsViewModel 增加构造参数 `pairing: PairingService`，新增：
```kotlin
private val _pairingStatus = MutableStateFlow("未配对")
val pairingStatus: StateFlow<String> = _pairingStatus

suspend fun pairWithKey(keyInput: String): Boolean {
  val result = pairing.pair(keyInput)
  return when (result) {
    is AuthResult.Success -> {
      prefs.baseUrl = result.origin.serialized
      _pairingStatus.value = "已配对 · ${result.deviceId.takeLast(8)}"
      _status.value = "已连接"
      true
    }
    is AuthResult.Failure -> {
      _pairingStatus.value = "配对失败: ${result.message}"
      _status.value = "配对失败: ${result.message}"
      false
    }
  }
}

fun markPaired(deviceId: String) { _pairingStatus.value = "已配对 · ${deviceId.takeLast(8)}" }
```

- [ ] **Step 4: 实现 SettingsScreen 配对区**

在网关地址字段与「测试并保存」之间插入配对区：
```kotlin
Text("配对", style = MaterialTheme.typography.titleMedium)
Row {
  OutlinedTextField(value = pairInput, onValueChange = { pairInput = it },
    label = { Text("appKey 或配对 URL") }, singleLine = true,
    modifier = Modifier.weight(1f))
  Button(onClick = { /* launch ScanActivity via rememberLauncherForActivityResult */ }) { Text("扫码") }
}
Button(onClick = {
  pairing = true
  scope.launch { viewModel.pairWithKey(pairInput.trim()); pairing = false }
}, enabled = !pairing) { Text(if (pairing) "配对中…" else "配对") }
Text(viewModel.pairingStatus.collectAsState().value)
```

扫码：`rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == Activity.RESULT_OK) viewModel.registerScanResult(result.data?.getStringExtra("pairing_text").orEmpty()) }`，launch 前 `ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), ...)` 或直接检查已有权限；无权限时提示。

- [ ] **Step 5: 改造 AppRoot + MainActivity**

MainActivity：
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs = AmadeusPrefs.from(this)
  val sound = AmbientSound(this)
  val credentialStore = KeystoreDeviceCredentialStore(
    SharedPrefsStore(getSharedPreferences("amadeus_device", Context.MODE_PRIVATE)))
  val bootstrapClient = trustAllClient()
  val nativeAuth = NativeAuthClient(bootstrapClient) { caDer, instanceId ->
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
      .sslSocketFactory(PinnedTls.socketFactory(caDer, instanceId), PinnedTls.trustManager(caDer, instanceId))
      .build()
  }
  val authClient = AmadeusAuthClient(nativeAuth, credentialStore, bootstrapClient)
  val pairing = AmadeusPairingService(authClient)
  setContent { AppRoot(prefs, sound, pairing, authClient) }
}
```

AppRoot 签名改为 `AppRoot(prefs, sound, pairing: PairingService, authClient: AmadeusAuthClient)`。启动逻辑：
```kotlin
var sessionClient by remember { mutableStateOf<OkHttpClient?>(null) }
LaunchedEffect(Unit) {
  val saved = prefs.baseUrl
  if (saved != null) {
    val origin = runCatching { GatewayOrigin.parse(saved) }.getOrNull()
    if (origin != null) {
      when (val r = pairing.restore(origin)) {
        is AuthResult.Success -> { sessionClient = r.client; screen = Screen.Real }
        is AuthResult.Failure -> { prefs.clear(); screen = Screen.Demo }
      }
      return@LaunchedEffect
    }
  }
  screen = Screen.Demo
}
```

Real 分支：`val client = sessionClient`（非空才进 Real）；`apiOf`/stream 用该 client。`SettingsScreen` 传 pairing + 扫码回调 + isRealMode。断开连接：`authClient.clear(origin)` + `prefs.clear()` + `sessionClient=null` + Demo。

（实现细节以现有 AppRoot 结构为准：`apiOf` 从裸 client 改为 sessionClient 闭包；SettingsViewModel 构造传 pairing。）

- [ ] **Step 6: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest -q` 与 `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: 全绿 + BUILD SUCCESSFUL。

- [ ] **Step 7: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/AppRoot.kt app/amadeus-android/app/src/main/java/com/amadeus/whale/MainActivity.kt app/amadeus-android/app/src/main/java/com/amadeus/whale/settings app/amadeus-android/app/src/main/java/com/amadeus/whale/pairing/AmadeusPairingService.kt app/amadeus-android/app/src/test/java/com/amadeus/whale/settings/SettingsViewModelTest.kt
git commit -m "feat(app): pairing UI and authenticated session wiring"
```

---

### Task 10: P1-4 demo 环境音素材 + 资源检查

**Files:**
- Create: `app/amadeus-android/app/src/main/res/raw/sfx_wave.ogg`（或 .mp3/.wav）
- Create: `app/amadeus-android/app/src/main/res/raw/sfx_bell.ogg`
- Create: `app/amadeus-android/app/src/main/res/raw/bgm_rain.ogg`
- Create: `app/amadeus-android/app/src/main/res/raw/README.md`（来源记录）

**Interfaces:**
- Consumes: `AmbientSound` 已用 `context.resources.getIdentifier(name, "raw", packageName)` 按名解析；文件就位后自动生效。

**约束**：素材须 CC0/开源；`AmbientSound` 的 `play("wave")`/`play("bell")`/`playBgm("rain")` 需能播（当前 getIdentifier 找不到返回 id=0 → 静音降级，不回归）。

- [ ] **Step 1: 获取素材**

从公开 CC0/开源来源获取三个短音频（海浪、提示铃、雨声 bgm，各 ≤3s 海浪/铃、≥20s 雨声循环），转 ogg（Android 原生 MediaPlayer 支持 ogg vorbis）放入 `res/raw/`。若网络不可用或找不到干净授权素材，**跳过文件**并在 README 记录"静音降级保留"，测试继续通过。

- [ ] **Step 2: README 记录来源**

`res/raw/README.md` 写来源 URL/许可（例：freesound.org CC0 页面链接、可在线验证的源）。

- [ ] **Step 3: 资源存在性测试**

在 `theatre/AmbientSoundTest.kt` 增加（若素材就位）或新增 `RawAssetTest.kt`：
```kotlin
class RawAssetTest {
  @Test fun demoAmbientAssetsPresent() {
    // 通过 packageName 解析（JVM 单测无资源 → 跳过；此测试在 instrumented 或构建期 lint 执行）
    // 在 gradle 侧确保 res/raw 三个文件存在（lint 或 assembleDebug 产物包含）
  }
}
```
若无法在 JVM 单测验证资源，改在 `app/build.gradle.kts` 加一个简单 task/检查或在 README 标注。**不强求测试**；验收以 assembleDebug 后 `res/raw` 文件是否打包为准。

- [ ] **Step 4: 编译 + 打包确认**

Run: `.\gradlew.bat :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL；APK 内 `res/raw/` 含三个文件（如素材就位）。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/res/raw
git commit -m "feat(app): demo ambient sound assets (cc0) with source notes"
```

---

### Self-Review（控制器执行）

- **Spec 覆盖**：P0-1 4a-4f 全部任务覆盖（解析/解码/TLS/auth/凭据/编排/扫码/UI/接线/测试）。4d 自动续期由 Task 9 AppRoot restore 实现；401 自动续期逻辑在 Task 9 说明中（业务 401 → 调 restore）。4e 错误文案由 SettingsViewModel + PairingService 覆盖。
- **占位符扫描**：无 TBD；Task 4/6/7/9 的"注意"是指导性修正说明（实现者必须落实，非占位）。
- **类型一致性**：`PairingScanTarget`（Task 1）在 Task 7/9 复用；`AuthResult`（Task 7）在 Task 9 复用；`PairingService`（Task 9）在 MainActivity 复用；`AmadeusAuthClient` 签名跨 Task 7/9 一致。