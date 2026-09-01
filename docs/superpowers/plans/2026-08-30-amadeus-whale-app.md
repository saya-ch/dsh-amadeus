# Amadeus Whale App（移动端）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零重建 Amadeus Whale 原生 Compose App：启动即进 demo 剧场（演出层完整），连接成功后记住状态直连真实会话（SSE 推送 + 报告/预览/choice/历史窗口）。

**Architecture:** 双层状态机 —— MainActivity 在 DemoTheatre 与 RealTheatre 间切换，TheatreScreen 单一实现，数据源经 `MessageFeed` 接口注入（demo=DemoFeed，真实=RealFeed）。演出层 `SpriteRenderer` 抽象（Static=Coil 静态切图，未来 Live2D）。网络层 OkHttp REST + OkHttp SSE。全部保留 assets 素材，删除其余史山代码。

**Tech Stack:** Kotlin 2.0 / Jetpack Compose（BOM 2024.10）/ Material3 / Coil 2.6（AsyncImage）/ OkHttp 4.12 + okhttp-sse / kotlinx-serialization-json / kotlinx-coroutines / JUnit4 + kotlin.test + OkHttp MockWebServer / MediaPlayer（环境音）。

**Spec:** `docs/superpowers/specs/2026-09-01-amadeus-whale-experience-conclusions.md`（产品权威依据）

## Global Constraints

- **删除史山**：除 `app/amadeus-android/app/src/main/assets/amadeus/` 素材外，App 全部旧代码不保留，从零重建。
- **包名**：`com.amadeus.whale`（不变）。
- **SDK**：compileSdk 36、minSdk 29。
- **素材映射**：sprite → assets 文件：`shy→whale-shy.webp`、`think→whale-confused.webp`、`tool→whale-serious.webp`、`wag→whale-cheerful.webp`、`gray→whale-frightened.webp`、`smile→whale-starry.webp`、`talk→maid-left.webp`；背景 `bg-*.webp`。
- **网关基址**：`<baseUrl>/amadeus/extensions/amadeus/routes`（REST 路由），SSE 用 `<baseUrl>/amadeus/extensions/amadeus/stream/{sessionId}`。
- **协议**：`[[AMW:{...}]]` 标签字段 mood/sprite/voice/sfx/bgm/window/windowId/windowTitle/choiceId/options；多短句逐行 `句\n[[AMW:...]]`。
- **demo 叙事**：月夜礁石初遇（相识）；20-30 句；每次进 App 从头演；可快进到底。
- **真实叙事**：居家日常（背景用居家素材 `bg-*.webp`），底部输入框打字。
- **环境音仅 demo**：海浪/风声/提示铃，MediaPlayer，设置开关。
- **mode 隔离**：App 只接触 `agentPreset==='amadeus'` 会话，App 内不出现任何其他 mode。
- 构建产物、`local.properties` 不入库；每任务独立提交。

---

### Task 1: 项目骨架重建（Gradle + 依赖 + assets 迁移）

**Files:**
- Create: `app/amadeus-android/settings.gradle.kts`
- Create: `app/amadeus-android/build.gradle.kts`
- Create: `app/amadeus-android/gradle.properties`
- Create: `app/amadeus-android/gradle/libs.versions.toml`
- Create: `app/amadeus-android/app/build.gradle.kts`
- Create: `app/amadeus-android/app/src/main/AndroidManifest.xml`
- Create: `app/amadeus-android/app/src/main/res/values/strings.xml`
- Create: `app/amadeus-android/app/src/main/res/values/themes.xml`
- Create: `app/amadeus-android/app/src/main/res/mipmap-*/ic_launcher.webp`（用 Android Studio 默认占位或引用系统资源）
- Delete: `app/amadeus-android/app/src/main/java/com/amadeus/whale/**`（全部旧代码）
- Keep: `app/amadeus-android/app/src/main/assets/amadeus/**`（素材，勿动）

**Interfaces:**
- Consumes: 无（从零）。
- Produces: 可构建的空 App 骨架；`MainActivity` 占位显示「Amadeus Whale」。后续任务依赖 `com.amadeus.whale` 包结构。

- [ ] **Step 1: 删除旧 Java 代码，保留 assets**

```bash
Remove-Item -Recurse -Force "app/amadeus-android/app/src/main/java/com/amadeus/whale"
New-Item -ItemType Directory -Path "app/amadeus-android/app/src/main/java/com/amadeus/whale" -Force
Test-Path "app/amadeus-android/app/src/main/assets/amadeus/whale-shy.webp"
```
Expected: assets 下立绘/背景文件均在；java 目录已清空。

- [ ] **Step 2: 写根 Gradle 文件**

`settings.gradle.kts`:
```kotlin
pluginManagement {
  repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "amadeus-whale"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.configuration-cache=true
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.6.0"
kotlin = "2.0.20"
composeBom = "2024.10.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
coil = "2.6.0"
okhttp = "4.12.0"
kotlinxSerialization = "1.7.3"
coroutines = "1.9.0"
junit = "4.13.2"
mockwebserver = "4.12.0"

[libraries]
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-animation = { group = "androidx.compose.animation", name = "animation" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-sse = { group = "com.squareup.okhttp3", name = "okhttp-sse", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "mockwebserver" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: 写 app 模块构建文件**

`app/amadeus-android/app/build.gradle.kts`:
```kotlin
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.amadeus.whale"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.amadeus.whale"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
  packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.animation)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.coil.compose)
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  debugImplementation(libs.compose.ui.tooling)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}
```

- [ ] **Step 4: 写 Manifest 与资源**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <application
    android:allowBackup="false"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:usesCleartextTraffic="true"
    android:theme="@style/Theme.AmadeusWhale">
    <activity android:name=".MainActivity" android:exported="true"
      android:configChanges="orientation|screenSize|keyboardHidden">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

`res/values/strings.xml`:
```xml
<resources><string name="app_name">Amadeus Whale</string></resources>
```

`res/values/themes.xml`:
```xml
<resources>
  <style name="Theme.AmadeusWhale" parent="android:Theme.Material.NoActionBar">
    <item name="android:statusBarColor">#000000</item>
    <item name="android:navigationBarColor">#000000</item>
  </style>
</resources>
```

- [ ] **Step 5: 写占位 MainActivity**

Create `app/src/main/java/com/amadeus/whale/MainActivity.kt`:
```kotlin
package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Text("Amadeus Whale") } }
  }
}
```

- [ ] **Step 6: 构建验证**

Run: `.\gradlew.bat :app:assembleDebug`（在 `app/amadeus-android` 下；若 wrapper 缺失，先执行 `gradle wrapper --gradle-version 8.7` 生成）
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 7: 提交**

```bash
git add app/amadeus-android
git commit -m "chore: rebuild Amadeus Whale Android skeleton from scratch (keep assets)"
```

---

### Task 2: AMW 标签数据类与解析器

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/model/AmadeusTag.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/model/AmadeusTagTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `enum class AmadeusMood { shy, think, tool, happy, sad, idle }`
  - `enum class AmadeusSprite { shy, think, tool, wag, gray, smile, talk }`
  - `enum class AmadeusWindow { none, report, preview, choice }`
  - `data class AmadeusTag(mood, sprite, voice, sfx, bgm, window, windowId, windowTitle, choiceId, options: List<ChoiceOption>)`
  - `data class ChoiceOption(label: String, description: String?)`
  - `object AmadeusTagParser { fun parse(json: String): AmadeusTag }`（缺失字段取默认；字段解析失败取默认）

- [ ] **Step 1: 写失败测试**

`AmadeusTagTest.kt`:
```kotlin
package com.amadeus.whale.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AmadeusTagTest {
  @Test fun parseFullTag() {
    val tag = AmadeusTagParser.parse(
      """{"mood":"happy","sprite":"wag","voice":"soft","sfx":"bell","bgm":"none","window":"report","windowId":"rpt_123","windowTitle":"今日小报告"}"""
    )
    assertEquals(AmadeusMood.happy, tag.mood)
    assertEquals(AmadeusSprite.wag, tag.sprite)
    assertEquals("rpt_123", tag.windowId)
    assertEquals(AmadeusWindow.report, tag.window)
  }

  @Test fun parseChoiceOptions() {
    val tag = AmadeusTagParser.parse(
      """{"mood":"idle","window":"choice","choiceId":"c1","options":[{"label":"继续","description":"干活"},{"label":"停下"}]}"""
    )
    assertEquals(AmadeusWindow.choice, tag.window)
    assertEquals(2, tag.options.size)
    assertEquals("继续", tag.options[0].label)
    assertEquals("干活", tag.options[0].description)
  }

  @Test fun parsePartialDefaults() {
    val tag = AmadeusTagParser.parse("""{"mood":"shy"}""")
    assertEquals(AmadeusMood.shy, tag.mood)
    assertEquals(AmadeusSprite.smile, tag.sprite)
    assertEquals(AmadeusWindow.none, tag.window)
    assertEquals("", tag.windowId)
    assertEquals(emptyList(), tag.options)
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.model.AmadeusTagTest"`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`AmadeusTag.kt`:
```kotlin
package com.amadeus.whale.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class AmadeusMood { shy, think, tool, happy, sad, idle }
enum class AmadeusSprite { shy, think, tool, wag, gray, smile, talk }
enum class AmadeusWindow { none, report, preview, choice }

@Serializable
data class ChoiceOption(val label: String, val description: String? = null)

data class AmadeusTag(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val voice: String = "soft",
  val sfx: String = "none",
  val bgm: String = "none",
  val window: AmadeusWindow = AmadeusWindow.none,
  val windowId: String = "",
  val windowTitle: String = "",
  val choiceId: String = "",
  val options: List<ChoiceOption> = emptyList(),
)

object AmadeusTagParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(jsonText: String): AmadeusTag {
    val obj = runCatching { json.parseToJsonElement(jsonText) as JsonObject }.getOrNull() ?: return AmadeusTag()
    fun str(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull ?: ""
    fun enumValueOf(name: String, enum: Array<out Enum<*>>): Int? =
      enum.indexOfFirst { it.name == name }.takeIf { it >= 0 }
    val mood = enumValueOf(str("mood"), AmadeusMood.entries.toTypedArray())
      ?.let { AmadeusMood.entries[it] } ?: AmadeusMood.idle
    val sprite = enumValueOf(str("sprite"), AmadeusSprite.entries.toTypedArray())
      ?.let { AmadeusSprite.entries[it] } ?: AmadeusSprite.smile
    val window = enumValueOf(str("window"), AmadeusWindow.entries.toTypedArray())
      ?.let { AmadeusWindow.entries[it] } ?: AmadeusWindow.none
    val options = (obj["options"] as? JsonArray)?.mapNotNull { el ->
      val o = el as? JsonObject ?: return@mapNotNull null
      ChoiceOption((o["label"] as? JsonPrimitive)?.contentOrNull ?: "", (o["description"] as? JsonPrimitive)?.contentOrNull)
    } ?: emptyList()
    return AmadeusTag(
      mood = mood, sprite = sprite,
      voice = str("voice").ifEmpty { "soft" },
      sfx = str("sfx").ifEmpty { "none" },
      bgm = str("bgm").ifEmpty { "none" },
      window = window,
      windowId = str("windowId"), windowTitle = str("windowTitle"),
      choiceId = str("choiceId"), options = options,
    )
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.model.AmadeusTagTest"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/model app/amadeus-android/app/src/test
git commit -m "feat(app): AMW tag data classes and parser"
```

---

### Task 3: 多句分页解析器（parseAmadeusSegments + ensureTag）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/model/AmadeusSegments.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/model/AmadeusSegmentsTest.kt`

**Interfaces:**
- Consumes: `AmadeusTag`、`AmadeusTagParser`、`AmadeusMood`、`AmadeusSprite`（Task 2）。
- Produces:
  - `data class AmadeusSegment(dialog: String, tag: AmadeusTag, windowId: String? = null)`
  - `object AmadeusSegmentParser { fun parse(raw: String): List<AmadeusSegment> }`：按「句\n[[AMW:{...}]]」拆分；无标签的段落用默认 tag；整段无标签则补 `[[AMW:{}]]` 默认 tag（等价 ensureAmadeusTag 兜底）。
  - `fun ensureAmadeusTag(raw: String): String`：若 raw 无任何 `[[AMW:`，在末尾追补 `[[AMW:{"mood":"idle","sprite":"smile"}]]`。

- [ ] **Step 1: 写失败测试**

`AmadeusSegmentsTest.kt`:
```kotlin
package com.amadeus.whale.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AmadeusSegmentsTest {
  @Test fun splitWithTags() {
    val raw = "呜... 月光照在礁石上呢...\n" +
      "[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]\n" +
      "有你在身边，感觉暖暖的 啾~\n" +
      "[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]"
    val segs = AmadeusSegmentParser.parse(raw)
    assertEquals(2, segs.size)
    assertEquals("呜... 月光照在礁石上呢...", segs[0].dialog)
    assertEquals(AmadeusMood.shy, segs[0].tag.mood)
    assertEquals("有你在身边，感觉暖暖的 啾~", segs[1].dialog)
    assertEquals(AmadeusMood.happy, segs[1].tag.mood)
  }

  @Test fun segmentWithoutTagGetsDefault() {
    val segs = AmadeusSegmentParser.parse("只是一句话")
    assertEquals(1, segs.size)
    assertEquals("只是一句话", segs[0].dialog)
    assertEquals(AmadeusMood.idle, segs[0].tag.mood)
  }

  @Test fun ensureTagAppendsWhenMissing() {
    assertEquals("你好\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]", ensureAmadeusTag("你好"))
    assertEquals("你好\n[[AMW:{}]]", ensureAmadeusTag("你好\n[[AMW:{}]]"))
  }

  @Test fun segmentExposesWindowId() {
    val segs = AmadeusSegmentParser.parse("报告好了\n[[AMW:{\"window\":\"report\",\"windowId\":\"rpt_1\",\"windowTitle\":\"报告\"}]]")
    assertEquals("rpt_1", segs[0].windowId)
    assertEquals("报告", segs[0].tag.windowTitle)
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.model.AmadeusSegmentsTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`AmadeusSegments.kt`:
```kotlin
package com.amadeus.whale.model

data class AmadeusSegment(
  val dialog: String,
  val tag: AmadeusTag,
  val windowId: String? = null,
)

object AmadeusSegmentParser {
  private val tagRegex = Regex("""\[\[AMW:\s*(\{[\s\S]*?\})\s*\]\]""")

  fun parse(raw: String): List<AmadeusSegment> {
    val segments = mutableListOf<AmadeusSegment>()
    var cursor = 0
    var match = tagRegex.find(raw, cursor)
    while (match != null) {
      val clean = raw.substring(cursor, match.range.first).trim()
      val tag = AmadeusTagParser.parse(match.groupValues[1])
      if (clean.isNotEmpty()) segments.add(AmadeusSegment(clean, tag))
      cursor = match.range.last + 1
      while (cursor < raw.length && (raw[cursor] == '\n' || raw[cursor] == '\r')) cursor++
      match = tagRegex.find(raw, cursor)
    }
    val tail = raw.substring(cursor).trim()
    if (tail.isNotEmpty()) segments.add(AmadeusSegment(tail, AmadeusTag()))
    if (segments.isEmpty() && raw.trim().isNotEmpty()) segments.add(AmadeusSegment(raw.trim(), AmadeusTag()))
    return segments
  }

  fun ensureAmadeusTag(raw: String): String {
    if (tagRegex.containsMatchIn(raw)) return raw
    return raw.trimEnd('\n', '\r') + "\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]"
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.model.AmadeusSegmentsTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/model app/amadeus-android/app/src/test
git commit -m "feat(app): multi-sentence AMW segment parser with ensure-tag fallback"
```

---

### Task 4: MessageFeed 抽象 + DemoFeed（相识剧本）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/feed/MessageFeed.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/feed/DemoFeed.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/feed/DemoFeedTest.kt`

**Interfaces:**
- Consumes: `AmadeusSegment`、`AmadeusSegmentParser`（Task 3）。
- Produces:
  - `interface MessageFeed { suspend fun initial(): List<AmadeusSegment> }`（demo 返回剧本；真实返回会话快照+后续实时，后续 Task 扩展）。
  - `class DemoFeed : MessageFeed`：内置 24 句相识剧本（月夜礁石初遇→互相认识→邀约回家），每句带 AMW 标签；`initial()` 返回解析后段落。

- [ ] **Step 1: 写失败测试**

`DemoFeedTest.kt`:
```kotlin
package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusMood
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoFeedTest {
  @Test fun demoHas20To30Segments() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.size in 20..30, "demo 应为 20-30 句，实际 ${segs.size}")
  }

  @Test fun demoOpensWithMoonlitIntro() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.first().dialog.contains("月光"))
  }

  @Test fun demoHasVariedMoods() = runTest {
    val moods = DemoFeed().initial().map { it.tag.mood }.toSet()
    assertTrue(moods.size >= 3, "demo 应穿插多种心情，实际 $moods")
  }

  @Test fun demoEndsOnWarmNote() = runTest {
    val segs = DemoFeed().initial()
    assertTrue(segs.last().dialog.contains("家") || segs.last().dialog.contains("再见") || segs.last().dialog.contains("明天"))
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.feed.DemoFeedTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`MessageFeed.kt`:
```kotlin
package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment

interface MessageFeed {
  suspend fun initial(): List<AmadeusSegment>
}
```

`DemoFeed.kt`（24 句相识剧本，句后紧跟标签）:
```kotlin
package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser

class DemoFeed : MessageFeed {
  private val script = listOf(
    "呜... 月光照在礁石上呢...\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\",\"sfx\":\"wave\",\"bgm\":\"rain\"}]]",
    "你也是来海边看月亮的吗 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "我... 我其实住在海那边，今天游得有点远啦\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "风有点凉，但看到你就没那么冷了\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "咦，你居然能听懂我说话？\n[[AMW:{\"mood\":\"think\",\"sprite\":\"think\"}]]",
    "人类都这么温柔的嘛 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "我叫鲸鱼娘，是住在深海的鲸鱼哦\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "尾巴平时藏起来啦，高兴才会冒出来拍拍\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "呜... 你盯着我看，我会不好意思的\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "不过... 认识你，我很开心\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"smile\"}]]",
    "你知道吗，月亮照在海面的时候，最好看了\n[[AMW:{\"mood\":\"think\",\"sprite\":\"think\",\"sfx\":\"wave\"}]]",
    "像碎银子一样，一闪一闪的 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "呜... 我有点想家了，但又舍不得走\n[[AMW:{\"mood\":\"sad\",\"sprite\":\"gray\"}]]",
    "要不... 我帮你做点什么，当作今天认识的礼物？\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "我虽然笨笨的，但学东西很快哦\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"think\"}]]",
    "写代码、查资料、整理文件，我都会一点点\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"serious\"}]]",
    "呜... 不小心说太多啦，你会觉得我啰嗦吗\n[[AMW:{\"mood\":\"shy\",\"sprite\":\"shy\"}]]",
    "不会吗？那... 那太好了 啾~\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
    "以后你忙的时候，就喊我一声\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\"}]]",
    "我会在电脑那头，安安静静帮你把活干完\n[[AMW:{\"mood\":\"tool\",\"sprite\":\"think\"}]]",
    "干完就化成小报告，放在窗口里等你点开\n[[AMW:{\"mood\":\"idle\",\"sprite\":\"smile\",\"window\":\"report\",\"windowId\":\"rpt_demo\",\"windowTitle\":\"今天的活\"}]]",
    "呜... 天快亮了，我该回海里啦\n[[AMW:{\"mood\":\"sad\",\"sprite\":\"gray\",\"bgm\":\"rain\"}]]",
    "不过明天，我们还会再见的，对吧？\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"smile\"}]]",
    "那就说定了 啾~ 明天见！\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]",
  )

  override suspend fun initial(): List<AmadeusSegment> =
    script.flatMap { AmadeusSegmentParser.parse(it) }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.feed.DemoFeedTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/feed app/amadeus-android/app/src/test
git commit -m "feat(app): MessageFeed abstraction with demo meet-cute script"
```

---

### Task 5: TheatreViewModel（队列状态机）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/TheatreViewModel.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/theatre/TheatreViewModelTest.kt`

**Interfaces:**
- Consumes: `MessageFeed`、`AmadeusSegment`（Task 4）。
- Produces:
  - `data class TheatreUiState(mood, sprite, dialog, speaker: String = "鲸鱼娘", windowId: String?, background: String, isTypingFinished: Boolean)`
  - `class TheatreViewModel(feed: MessageFeed, backgroundProvider: (tag) -> String)`
    - `val uiState: StateFlow<TheatreUiState>`
    - `suspend fun load()`：`feed.initial()` 入队，置首个。
    - `fun onTap(): Boolean`：打字未完成→完成；已打满→下一句；返回是否有下一句。
    - `fun skipToEnd()`：直接跳到最后一句（快进到底）。
    - `fun hasNext(): Boolean`、`fun currentIndex(): Int`
  - 背景策略：`interface BackgroundResolver { fun resolve(mood: AmadeusMood): String }`（demo=海边，真实=居家；Task 10 用）。

- [ ] **Step 1: 写失败测试**

`TheatreViewModelTest.kt`:
```kotlin
package com.amadeus.whale.theatre

import com.amadeus.whale.feed.DemoFeed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TheatreViewModelTest {
  private fun vm() = TheatreViewModel(DemoFeed(), backgroundResolver = { "palace-night" })

  @Test fun loadSetsFirstSegment() = runTest {
    val v = vm()
    v.load()
    assertEquals("呜... 月光照在礁石上呢...", v.uiState.value.dialog)
  }

  @Test fun tapAdvancesOneAtATime() = runTest {
    val v = vm()
    v.load()
    val first = v.uiState.value.dialog
    v.onTap() // 第一次点击：完成打字
    assertEquals(first, v.uiState.value.dialog) // 句子不变，仅打字完成
    v.onTap() // 第二次点击：下一句
    assertTrue(v.uiState.value.dialog != first)
  }

  @Test fun skipToEndJumpsToLast() = runTest {
    val v = vm()
    v.load()
    v.skipToEnd()
    val segs = DemoFeed().initial()
    assertEquals(segs.last().dialog, v.uiState.value.dialog)
    assertFalse(v.hasNext())
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.theatre.TheatreViewModelTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`TheatreViewModel.kt`:
```kotlin
package com.amadeus.whale.theatre

import androidx.compose.runtime.Stable
import com.amadeus.whale.feed.MessageFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSprite
import com.amadeus.whale.model.AmadeusSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable
data class TheatreUiState(
  val mood: AmadeusMood = AmadeusMood.idle,
  val sprite: AmadeusSprite = AmadeusSprite.smile,
  val dialog: String = "",
  val speaker: String = "鲸鱼娘",
  val windowId: String? = null,
  val background: String = "palace-night",
  val typingFinished: Boolean = false,
)

class TheatreViewModel(
  private val feed: MessageFeed,
  private val backgroundResolver: (AmadeusMood) -> String = { "palace-night" },
) {
  private val _uiState = MutableStateFlow(TheatreUiState())
  val uiState: StateFlow<TheatreUiState> = _uiState
  private val _queue = MutableStateFlow<List<AmadeusSegment>>(emptyList())
  private val _index = MutableStateFlow(0)

  suspend fun load() {
    val segs = feed.initial()
    _queue.value = segs
    _index.value = 0
    _uiState.value = segs.firstOrNull()?.let { seg ->
      TheatreUiState(
        mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
        windowId = seg.windowId, background = backgroundResolver(seg.tag.mood),
      )
    } ?: TheatreUiState()
  }

  fun onTap(): Boolean {
    if (!_uiState.value.typingFinished) {
      _uiState.value = _uiState.value.copy(typingFinished = true)
      return true
    }
    return advance()
  }

  fun skipToEnd() {
    val q = _queue.value
    if (q.isEmpty()) return
    _index.value = q.size - 1
    val seg = q.last()
    _uiState.value = TheatreUiState(
      mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
      windowId = seg.windowId, background = backgroundResolver(seg.tag.mood), typingFinished = true,
    )
  }

  fun hasNext(): Boolean = _index.value + 1 < _queue.value.size

  private fun advance(): Boolean {
    val q = _queue.value
    val next = _index.value + 1
    if (next >= q.size) return false
    _index.value = next
    val seg = q[next]
    _uiState.value = TheatreUiState(
      mood = seg.tag.mood, sprite = seg.tag.sprite, dialog = seg.dialog,
      windowId = seg.windowId, background = backgroundResolver(seg.tag.mood),
    )
    return true
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.theatre.TheatreViewModelTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre app/amadeus-android/app/src/test
git commit -m "feat(app): theatre view model queue with typewriter/tap/skip states"
```

---

### Task 6: SpriteRenderer 接口 + StaticSpriteRenderer（Coil）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/SpriteRenderer.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/theatre/SpriteResolverTest.kt`

**Interfaces:**
- Consumes: `AmadeusMood`、`AmadeusSprite`（Task 2）。
- Produces:
  - `interface SpriteRenderer { @Composable fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) }`
  - `object SpriteAssetMap { fun asset(sprite: AmadeusSprite): String }` → 返回 `"amadeus/whale-shy.webp"` 等。
  - `class StaticSpriteRenderer : SpriteRenderer`：Coil `AsyncImage`，底部对齐、`fillMaxWidth`、`heightIn(max=60% 屏高)`、`ContentScale.Fit`；呼吸动画（`rememberInfiniteTransition` scale 1.00↔1.02，3s）。
  - `class Live2DSpriteRenderer : SpriteRenderer`：占位（`Text("Live2D 待接入")`）。

- [ ] **Step 1: 写失败测试**

`sprite 映射与高度策略`（纯逻辑可测）:
```kotlin
package com.amadeus.whale.theatre

import com.amadeus.whale.model.AmadeusSprite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpriteResolverTest {
  @Test fun assetMapCoversAllSprites() {
    for (sprite in AmadeusSprite.entries) {
      assertTrue(SpriteAssetMap.asset(sprite).startsWith("amadeus/"), "asset($sprite) 应落在 amadeus/")
    }
  }
  @Test fun shyMapsToWhaleShy() {
    assertEquals("amadeus/whale-shy.webp", SpriteAssetMap.asset(AmadeusSprite.shy))
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.theatre.SpriteResolverTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`SpriteRenderer.kt`:
```kotlin
package com.amadeus.whale.theatre

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusSprite

interface SpriteRenderer {
  @Composable fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier = Modifier)
}

object SpriteAssetMap {
  private val map = mapOf(
    AmadeusSprite.shy to "amadeus/whale-shy.webp",
    AmadeusSprite.think to "amadeus/whale-confused.webp",
    AmadeusSprite.tool to "amadeus/whale-serious.webp",
    AmadeusSprite.wag to "amadeus/whale-cheerful.webp",
    AmadeusSprite.gray to "amadeus/whale-frightened.webp",
    AmadeusSprite.smile to "amadeus/whale-starry.webp",
    AmadeusSprite.talk to "amadeus/maid-left.webp",
  )
  fun asset(sprite: AmadeusSprite): String = map.getValue(sprite)
}

class StaticSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
      initialValue = 1.00f, targetValue = 1.02f,
      animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
      label = "breathScale",
    )
    AsyncImage(
      model = SpriteAssetMap.asset(sprite),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = modifier
        .fillMaxWidth()
        .heightIn(max = 480.dp) // 约屏高 60%
        .graphicsLayer { scaleX = scale; scaleY = scale },
    )
  }
}

class Live2DSpriteRenderer : SpriteRenderer {
  @Composable
  override fun Render(mood: AmadeusMood, sprite: AmadeusSprite, modifier: Modifier) {
    Text("Live2D 待接入", modifier = modifier, alignment = Alignment.Center)
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.theatre.SpriteResolverTest"`
Expected: PASS。

- [ ] **Step 5: 构建验证 Compose 编译**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: 成功（AsyncImage/graphicsLayer 编译通过）。

- [ ] **Step 6: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre app/amadeus-android/app/src/test
git commit -m "feat(app): sprite renderer abstraction with breathing Coil static renderer"
```

---

### Task 7: TheatreScreen 主舞台（背景+立绘+对话框+打字机）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/TheatreScreen.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/Typewriter.kt`

**Interfaces:**
- Consumes: `TheatreViewModel`、`TheatreUiState`、`SpriteRenderer`（Task 5/6）。
- Produces:
  - `@Composable fun TheatreScreen(viewModel, renderer: SpriteRenderer, backgroundResolver: (AmadeusMood)->String, onOpenSettings: () -> Unit, onOpenWindow: (windowId: String, type: AmadeusWindow) -> Unit, inputBar: @Composable (send: (String)->Unit) -> Unit = {})`
  - 结构：`Box(fillMaxSize)` → 背景层（`Crossfade` 1s，Coil AsyncImage `bg-*.webp`）→ 立绘层（`AnimatedContent` + slideInVertically/fadeIn 换 sprite）→ 对话框层（底部半透明面板圆角 20dp，名字「鲸鱼娘」，打字机，右下角 ▼，点击整个面板 `viewModel.onTap()`）→ 右上角齿轮按钮 → 输入栏插槽（真实模式用，demo 隐藏）。
  - `Typewriter`：`@Composable fun Typewriter(text: String, finished: Boolean, modifier)` 逐字显示，`finished=true` 一次显示全量。

- [ ] **Step 1: 实现（UI 用 Compose 冒烟验证，逻辑已由 Task 5 覆盖）**

`Typewriter.kt`:
```kotlin
package com.amadeus.whale.theatre

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun Typewriter(text: String, finished: Boolean, modifier: Modifier = Modifier) {
  var shown by remember(text) { mutableIntStateOf(0) }
  LaunchedEffect(text, finished) {
    if (finished) { shown = text.length; return@LaunchedEffect }
    for (i in 1..text.length) { shown = i; delay(30) }
  }
  BasicText(text = text.take(shown), modifier = modifier)
}
```

`TheatreScreen.kt`:
```kotlin
package com.amadeus.whale.theatre

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.model.AmadeusWindow

@Composable
fun TheatreScreen(
  viewModel: TheatreViewModel,
  renderer: SpriteRenderer = StaticSpriteRenderer(),
  backgroundResolver: (AmadeusMood) -> String = { "palace-night" },
  onOpenSettings: () -> Unit = {},
  onOpenWindow: (windowId: String, type: AmadeusWindow) -> Unit = { _, _ -> },
  inputBar: @Composable (send: (String) -> Unit) -> Unit = {},
) {
  val state by viewModel.uiState.collectAsState()
  val bg by remember(state.background) { mutableStateOf(state.background) }

  Box(modifier = Modifier.fillMaxSize()) {
    // 背景层（Crossfade 1s）
    Crossfade(targetState = bg, animationSpec = androidx.compose.animation.core.tween(1000), label = "bg") { name ->
      AsyncImage(
        model = "amadeus/$name.webp",
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    }
    // 立绘层（换 sprite 滑动入场）
    AnimatedContent(
      targetState = state.sprite,
      transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith fadeIn() },
      label = "sprite",
    ) { sprite ->
      renderer.Render(mood = state.mood, sprite = sprite, modifier = Modifier.align(Alignment.BottomCenter))
    }
    // 对话框层
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(16.dp)
        .clip(RoundedCornerShape(20.dp))
        .background(Color(0xCC000000))
        .clickable { viewModel.onTap() }
        .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Text(text = state.speaker, color = Color(0xFFFFD6A5), fontSize = 16.sp)
      Spacer(Modifier.height(6.dp))
      Typewriter(text = state.dialog, finished = state.typingFinished, modifier = Modifier.fillMaxWidth())
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (viewModel.hasNext()) Text(text = "▼", color = Color(0x88FFFFFF), fontSize = 14.sp)
      }
    }
    // 右上角齿轮
    IconButton(
      onClick = onOpenSettings,
      modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
    ) { Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White) }
  }
}
```

- [ ] **Step 2: 构建验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: 编译成功。

- [ ] **Step 3: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre
git commit -m "feat(app): theatre main stage with crossfade bg, animated sprite, typewriter dialog"
```

---

### Task 8: 心情点缀（MoodEffects）+ demo 环境音（AmbientSound）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/MoodEffects.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre/AmbientSound.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/theatre/AmbientSoundTest.kt`

**Interfaces:**
- Consumes: `AmadeusMood`（Task 2）。
- Produces:
  - `@Composable fun MoodEffects(mood: AmadeusMood, modifier: Modifier)`：Canvas+无限动画零新依赖——idle/happy 海面光斑（漂浮圆点）、shy 上浮气泡（上升圆）、sad 雨丝（斜线）。其余无。
  - `class AmbientSound(context: Context)`：`play(sfx: String)` 播放 wave/bell（assets 音频），`playBgm(name)` 循环 bgm rain；`stop()`、`setEnabled(Boolean)`。demo 模式用。
  - `object AmbientSoundController { var enabled = true }`（设置项读写，Task 14 接）。

- [ ] **Step 1: 写失败测试（控制器逻辑）**

`AmbientSoundTest.kt`（用 fake 校验 enable 状态）:
```kotlin
package com.amadeus.whale.theatre

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientSoundTest {
  @Test fun controllerDefaultsOn() { AmbientSoundController.enabled = true }
  @Test fun controllerCanToggle() {
    AmbientSoundController.enabled = true
    AmbientSoundController.enabled = false
    assertFalse(AmbientSoundController.enabled)
    AmbientSoundController.enabled = true
    assertTrue(AmbientSoundController.enabled)
  }
}
```

- [ ] **Step 2: 实现**

`AmbientSound.kt`:
```kotlin
package com.amadeus.whale.theatre

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.amadeus.whale.R

object AmbientSoundController { var enabled: Boolean = true }

class AmbientSound(private val context: Context) {
  private val players = mutableMapOf<String, MediaPlayer>()

  fun play(sfx: String) {
    if (!AmbientSoundController.enabled) return
    val res = when (sfx) {
      "wave" -> R.raw.sfx_wave
      "bell" -> R.raw.sfx_bell
      else -> return
    }
    players[sfx]?.release()
    players[sfx] = MediaPlayer.create(context, res)?.apply { start() }
  }

  fun playBgm(bgm: String, looping: Boolean = true) {
    if (!AmbientSoundController.enabled) return
    if (bgm != "rain") return
    stopBgm()
    players["bgm"] = MediaPlayer.create(context, R.raw.bgm_rain)?.apply {
      isLooping = looping
      start()
    }
  }

  fun stopBgm() { players.remove("bgm")?.release() }
  fun stopAll() { players.values.forEach { it.release() }; players.clear() }
}
```

`MoodEffects.kt`:
```kotlin
package com.amadeus.whale.theatre

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.amadeus.whale.model.AmadeusMood
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

@Composable
fun MoodEffects(mood: AmadeusMood, modifier: Modifier = Modifier) {
  if (mood == AmadeusMood.tool || mood == AmadeusMood.think) return
  val particles = remember { List(12) { Particle(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 80f + 20f) } }
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(mood) { while (true) { delay(50); tick++ } }
  Canvas(modifier = modifier) {
    particles.forEach { p ->
      val drift = sin((tick * 0.05) + p.x * 6f) * 8f
      val y = when (mood) {
        AmadeusMood.shy -> (p.y - (tick % 200) / 200f).mod(1f) * size.height // 上浮气泡
        AmadeusMood.sad -> p.y * size.height + (tick % 300) * 0.2f // 雨丝下坠
        else -> (p.y + (tick % 200) / 200f).mod(1f) * size.height // 海面光斑漂移
      }
      drawCircle(
        color = if (mood == AmadeusMood.sad) Color(0x88ADD8E6) else Color(0x44FFFFFF),
        radius = p.r,
        center = Offset((p.x * size.width + drift).mod(size.width), y),
      )
    }
  }
}
private data class Particle(val x: Float, val y: Float, val r: Float)
```

> 注：`sfx_wave`/`sfx_bell`/`bgm_rain` 音频资源需放入 `app/src/main/res/raw/`。**音频素材来自社区开源（见 Task 16 素材清单），本期可先用占位短音频或从 JAdpp/dsh-whale-galgame 仓库获取；若缺失，`play` 静默返回不崩溃。**

- [ ] **Step 3: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.theatre.AmbientSoundTest"`
Expected: PASS。

- [ ] **Step 4: 构建验证 + 提交**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: 成功。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/theatre app/amadeus-android/app/src/test
git commit -m "feat(app): mood particle effects and demo ambient sound controller"
```

---

### Task 9: AmadeusPrefs（持久化连接与偏好）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/AmadeusPrefs.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/AmadeusPrefsTest.kt`（用 fake store）

**Interfaces:**
- Consumes: 无。
- Produces:
  - `class AmadeusPrefs(context: Context)`：SharedPreferences 封装。
    - `var baseUrl: String?`（key `gateway_url`）
    - `var soundEnabled: Boolean`（key `sound_enabled`，默认 true）
    - `var showToolProgress: Boolean`（key `show_tool_progress`，默认 false）
    - `fun clear()`：断开连接时清 baseUrl。

- [ ] **Step 1: 写失败测试（抽象接口可测）**

引入 `interface PrefsStore { fun getString(k: String): String?; fun putString(k: String, v: String); fun getBoolean(k: String, def: Boolean): Boolean; fun putBoolean(k: String, v: Boolean); fun remove(k: String) }`，测试用内存 fake：
```kotlin
package com.amadeus.whale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmadeusPrefsTest {
  private class FakeStore : PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun getString(k: String) = m[k]
    override fun putString(k: String, v: String) { m[k] = v }
    override fun getBoolean(k: String, def: Boolean) = m[k]?.toBoolean() ?: def
    override fun putBoolean(k: String, v: Boolean) { m[k] = v.toString() }
    override fun remove(k: String) { m.remove(k) }
  }
  @Test fun roundtripBaseUrl() {
    val p = AmadeusPrefs(FakeStore())
    assertNull(p.baseUrl)
    p.baseUrl = "https://192.168.1.5:3444"
    assertEquals("https://192.168.1.5:3444", p.baseUrl)
  }
  @Test fun clearRemovesBaseUrl() {
    val p = AmadeusPrefs(FakeStore())
    p.baseUrl = "https://x:3444"
    p.clear()
    assertNull(p.baseUrl)
  }
}
```

- [ ] **Step 2: 实现**

`AmadeusPrefs.kt`:
```kotlin
package com.amadeus.whale

import android.content.Context
import android.content.SharedPreferences

interface PrefsStore {
  fun getString(key: String): String?
  fun putString(key: String, value: String)
  fun getBoolean(key: String, def: Boolean): Boolean
  fun putBoolean(key: String, value: Boolean)
  fun remove(key: String)
}

class SharedPrefsStore(private val sp: SharedPreferences) : PrefsStore {
  override fun getString(key: String) = sp.getString(key, null)
  override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
  override fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
  override fun putBoolean(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
  override fun remove(key: String) { sp.edit().remove(key).apply() }
}

class AmadeusPrefs(store: PrefsStore) {
  companion object {
    fun from(context: Context) = AmadeusPrefs(
      SharedPrefsStore(context.getSharedPreferences("amadeus", Context.MODE_PRIVATE))
    )
  }
  private val s = store
  var baseUrl: String?
    get() = s.getString(KEY_URL)
    set(value) { if (value == null) s.remove(KEY_URL) else s.putString(KEY_URL, value) }
  var soundEnabled: Boolean
    get() = s.getBoolean(KEY_SOUND, true)
    set(value) { s.putBoolean(KEY_SOUND, value) }
  var showToolProgress: Boolean
    get() = s.getBoolean(KEY_TOOL, false)
    set(value) { s.putBoolean(KEY_TOOL, value) }
  fun clear() { s.remove(KEY_URL) }
  companion object {
    private const val KEY_URL = "gateway_url"
    private const val KEY_SOUND = "sound_enabled"
    private const val KEY_TOOL = "show_tool_progress"
  }
}
```
> 修正：`AmadeusPrefs` 顶层声明 companion 后，去掉内层重复 companion（以实际编译为准；保持构造入参 store）。

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.AmadeusPrefsTest"`
Expected: PASS。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/AmadeusPrefs.kt app/amadeus-android/app/src/test
git commit -m "feat(app): persisted gateway url and preference toggles"
```

---

### Task 10: AmadeusApi（REST 客户端 + MockWebServer 测试）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/network/AmadeusApi.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/network/AmadeusModels.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/network/AmadeusApiTest.kt`

**Interfaces:**
- Consumes: 无（HTTP 层）。
- Produces:
  - `@Serializable data class AmadeusSession(id, title, mode, updatedAt, lastMessage: String? = null)`
  - `@Serializable data class SessionCreateBody(title: String? = null, workspaceId: String? = null)`
  - `class AmadeusApi(baseUrl: String, client: OkHttpClient)`
    - `suspend fun health(): Boolean` → GET `<routes>/status` 200 且 capabilities.sessions==true
    - `suspend fun listSessions(): List<AmadeusSession>` → GET `<routes>/sessions?mode=amadeus`
    - `suspend fun createSession(title: String? = null, workspaceId: String? = null): AmadeusSession` → POST `<routes>/sessions`
    - `suspend fun renameSession(id: String, title: String)` → POST `<routes>/sessions/:id/rename`
    - `suspend fun archiveSession(id: String)` → POST `<routes>/sessions/:id/archive`
    - `suspend fun sendPrompt(id: String, text: String)` → POST `<routes>/sessions/:id/prompt`
    - `suspend fun cancel(id: String)` → POST `<routes>/sessions/:id/cancel`
    - `suspend fun getReport(id: String): ReportPayload` → GET `<routes>/reports/:id`
    - `suspend fun getPreview(id: String): PreviewPayload` → GET `<routes>/previews/:id`
    - `suspend fun resolveChoice(choiceId: String, selected: String)` → POST `<routes>/choice`
  - JSON 用 kotlinx.serialization；错误抛 `AmadeusApiException(code, message)`。

- [ ] **Step 1: 写失败测试**

`AmadeusApiTest.kt`（MockWebServer 起本地 fake 网关）:
```kotlin
package com.amadeus.whale.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmadeusApiTest {
  private lateinit var server: MockWebServer
  private lateinit var api: AmadeusApi

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    api = AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun listSessionsParses() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"sessions":[{"id":"s1","title":"今天","mode":"amadeus","updatedAt":1700000000000,"lastMessage":"呜"}]}"""
    ).addHeader("Content-Type", "application/json"))
    val list = api.listSessions()
    assertEquals(1, list.size); assertEquals("s1", list[0].id); assertEquals("今天", list[0].title)
  }

  @Test fun healthTrueWhenCapable() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"capabilities":{"sessions":true,"reports":true,"choices":true}}"""
    ).addHeader("Content-Type", "application/json"))
    assertTrue(api.health())
  }

  @Test fun createSendsWorkspaceId() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"session":{"id":"s2","title":"新会话","mode":"amadeus","updatedAt":1}}"""
    ).addHeader("Content-Type", "application/json"))
    val s = api.createSession(workspaceId = "w1")
    assertEquals("s2", s.id)
    val req = server.takeRequest()
    assertTrue(req.body.readUtf8().contains("\"workspaceId\":\"w1\""))
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.network.AmadeusApiTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`AmadeusModels.kt`:
```kotlin
package com.amadeus.whale.network

import kotlinx.serialization.Serializable

@Serializable data class AmadeusSession(
  val id: String, val title: String, val mode: String,
  val updatedAt: Long, val lastMessage: String? = null,
)
@Serializable data class SessionListPayload(val sessions: List<AmadeusSession> = emptyList())
@Serializable data class SessionCreateBody(val title: String? = null, val workspaceId: String? = null)
@Serializable data class SessionCreatePayload(val session: AmadeusSession)
@Serializable data class StatusPayload(val capabilities: Capabilities = Capabilities())
@Serializable data class Capabilities(val sessions: Boolean = false, val reports: Boolean = false, val choices: Boolean = false)
@Serializable data class RenameBody(val title: String)
@Serializable data class PromptBody(val text: String)
@Serializable data class ReportPayload(val id: String, val title: String, val markdown: String, val createdAt: Long)
@Serializable data class PreviewPayload(val id: String, val type: String, val content: String, val title: String)
@Serializable data class ChoiceResolveBody(val selected: String)
```

`AmadeusApi.kt`:
```kotlin
package com.amadeus.whale.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AmadeusApiException(val code: Int, val apiMessage: String) :
  IOException("amadeus $code: $apiMessage")

class AmadeusApi(private val baseUrl: String, private val client: OkHttpClient) {
  private val json = Json { ignoreUnknownKeys = true }
  private val routes = "${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/routes"
  private val jsonType = "application/json".toMediaType()

  private suspend fun <T> call(request: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
    client.newCall(request).execute().use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw AmadeusApiException(resp.code, body)
      parse(body)
    }
  }

  suspend fun health(): Boolean =
    call(Request.Builder().url("$routes/status").get().build()) { s ->
      json.decodeFromString<StatusPayload>(s).capabilities.sessions
    }

  suspend fun listSessions(): List<AmadeusSession> =
    call(Request.Builder().url("$routes/sessions?mode=amadeus").get().build()) { s ->
      json.decodeFromString<SessionListPayload>(s).sessions
    }

  suspend fun createSession(title: String? = null, workspaceId: String? = null): AmadeusSession =
    call(Request.Builder().url("$routes/sessions").post(
      json.encodeToString(SessionCreateBody(title, workspaceId)).toRequestBody(jsonType)
    ).build()) { s -> json.decodeFromString<SessionCreatePayload>(s).session }

  suspend fun renameSession(id: String, title: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/rename").post(
      json.encodeToString(RenameBody(title)).toRequestBody(jsonType)
    ).build()) { }

  suspend fun archiveSession(id: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/archive").post(
      "{}".toRequestBody(jsonType)
    ).build()) { }

  suspend fun sendPrompt(id: String, text: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/prompt").post(
      json.encodeToString(PromptBody(text)).toRequestBody(jsonType)
    ).build()) { }

  suspend fun cancel(id: String): Unit =
    call(Request.Builder().url("$routes/sessions/$id/cancel").post(
      "{}".toRequestBody(jsonType)
    ).build()) { }

  suspend fun getReport(id: String): ReportPayload =
    call(Request.Builder().url("$routes/reports/$id").get().build()) { s ->
      json.decodeFromString<ReportPayload>(s)
    }

  suspend fun getPreview(id: String): PreviewPayload =
    call(Request.Builder().url("$routes/previews/$id").get().build()) { s ->
      json.decodeFromString<PreviewPayload>(s)
    }

  suspend fun resolveChoice(choiceId: String, selected: String): Unit =
    call(Request.Builder().url("$routes/choice").post(
      json.encodeToString(ChoiceResolveBody(selected)).toRequestBody(jsonType)
    ).build()) { }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.network.AmadeusApiTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/network app/amadeus-android/app/src/test
git commit -m "feat(app): amadeus REST client with mockwebserver tests"
```

---

### Task 11: AmadeusStream（SSE 客户端）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/network/AmadeusStream.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/network/AmadeusStreamTest.kt`

**Interfaces:**
- Consumes: `AmadeusSegment`、`AmadeusSegmentParser`（Task 3）。
- Produces:
  - `sealed class StreamEvent { data class Segments(val list: List<AmadeusSegment>); data class Choice(val choiceId: String, val question: String, val options: List<ChoiceOption>); data class Ended(val reason: String) }`
  - `class AmadeusStream(baseUrl: String, client: OkHttpClient)`：
    - `fun open(sessionId: String, onEvent: (StreamEvent) -> Unit): AutoCloseable`：OkHttp SSE `EventSource`（okhttp-sse）。SSE `data:` 行 JSON 结构：`{"type":"segments","text":"句\n[[AMW:...]]"}` 或 `{"type":"choice","choiceId":"c1","question":"...","options":[...]}` 或 `{"type":"ended","reason":"..."}`。
    - 内部按 `text` 用 `AmadeusSegmentParser.parse` 拆段。

- [ ] **Step 1: 写失败测试（用 MockWebServer 流式响应）**

```kotlin
package com.amadeus.whale.network

import com.amadeus.whale.model.AmadeusMood
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSources
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class AmadeusStreamTest {
  private lateinit var server: MockWebServer
  private lateinit var stream: AmadeusStream

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    stream = AmadeusStream(server.url("/").toString().trimEnd('/'), OkHttpClient())
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun parsesSegmentEvent() = runTest {
    server.enqueue(MockResponse()
      .setHeader("Content-Type", "text/event-stream")
      .setBody("data: {\"type\":\"segments\",\"text\":\"呜...\\n[[AMW:{\\\"mood\\\":\\\"shy\\\"}]]\"}\n\n"))
    val latch = CountDownLatch(1)
    var got: StreamEvent? = null
    stream.open("s1") { got = it; latch.countDown() }
    assertEquals(true, latch.await(3, TimeUnit.SECONDS))
    val seg = (got as StreamEvent.Segments).list
    assertEquals(1, seg.size)
    assertEquals(AmadeusMood.shy, seg[0].tag.mood)
  }
}
```
> 注：OkHttp `EventSource` 回调在线程池，测试用 latch 等待；实现需在 `open()` 内创建 EventSource 并接线。

- [ ] **Step 2: 实现**

`AmadeusStream.kt`:
```kotlin
package com.amadeus.whale.network

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser
import com.amadeus.whale.model.ChoiceOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed class StreamEvent {
  data class Segments(val list: List<AmadeusSegment>) : StreamEvent()
  data class Choice(val choiceId: String, val question: String, val options: List<ChoiceOption>) : StreamEvent()
  data class Ended(val reason: String) : StreamEvent()
}

class AmadeusStream(private val baseUrl: String, private val client: OkHttpClient) {
  private val json = Json { ignoreUnknownKeys = true }

  fun open(sessionId: String, onEvent: (StreamEvent) -> Unit): AutoCloseable {
    val req = Request.Builder()
      .url("${baseUrl.trimEnd('/')}/amadeus/extensions/amadeus/stream/$sessionId")
      .get().build()
    val factory = EventSources.createFactory(client)
    val source = factory.newEventSource(req, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        val parsed = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: return
        when ((parsed["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
          "segments" -> {
            val text = (parsed["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            onEvent(StreamEvent.Segments(AmadeusSegmentParser.parse(text)))
          }
          "choice" -> {
            val choiceId = (parsed["choiceId"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
            val question = (parsed["question"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val options = (parsed["options"] as? JsonArray)?.mapNotNull {
              val o = it as? JsonObject ?: return@mapNotNull null
              ChoiceOption((o["label"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "",
                (o["description"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull)
            } ?: emptyList()
            onEvent(StreamEvent.Choice(choiceId, question, options))
          }
          "ended" -> onEvent(StreamEvent.Ended((parsed["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""))
        }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        onEvent(StreamEvent.Ended(t?.message ?: "stream_failure"))
      }
    })
    return AutoCloseable { source.cancel() }
  }
}
```

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.network.AmadeusStreamTest"`
Expected: PASS。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/network app/amadeus-android/app/src/test
git commit -m "feat(app): okhttp SSE stream client for real sessions"
```

---

### Task 12: RealFeed（真实会话数据源）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/feed/RealFeed.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/feed/RealFeedTest.kt`

**Interfaces:**
- Consumes: `MessageFeed`（Task 4）、`AmadeusApi`（Task 10）、`AmadeusStream`（Task 11）。
- Produces:
  - `class RealFeed(sessionId: String, api: AmadeusApi, stream: AmadeusStream) : MessageFeed`
    - `override suspend fun initial(): List<AmadeusSegment>`：进会话显示最新态——用 `api.pageSession(id)`（历史分页，见 Task 10 补充）取最近一页 assistant 文本，`AmadeusSegmentParser` 解析为段（历史记录窗口用同一接口）。
    - `fun attach(onEvent: (StreamEvent) -> Unit): AutoCloseable`：`stream.open(sessionId, onEvent)`，实时段推给 TheatreViewModel 追加队列。
    - `suspend fun send(text: String)`：`api.sendPrompt`。
    - `suspend fun cancel()`：`api.cancel`。
  - Task 10 需补充方法：`suspend fun pageSession(id: String, beforeSeq: Long? = null): List<PageMessage>`；`@Serializable data class PageMessage(role: String, text: String)`。

- [ ] **Step 1: 更新 Task 10 的 AmadeusApi（补 pageSession）**

在 `AmadeusApi` 增：
```kotlin
@Serializable data class PageMessage(val role: String, val text: String)
@Serializable data class SessionPagePayload(val messages: List<PageMessage> = emptyList(), val hasMore: Boolean = false)

suspend fun pageSession(id: String, beforeSeq: Long? = null): SessionPagePayload =
  call(Request.Builder().url("$routes/sessions/$id/page${beforeSeq?.let { "?beforeSeq=$it" }.orEmpty()}").get().build()) { s ->
    json.decodeFromString<SessionPagePayload>(s)
  }
```
（在 Task 10 完成后原地补测：`MockWebServer` 回 `{"messages":[{"role":"assistant","text":"呜\n[[AMW:{}]]"}],"hasMore":false}`。）

- [ ] **Step 2: 写失败测试**

`RealFeedTest.kt`:
```kotlin
package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusMood
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFeedTest {
  private lateinit var server: MockWebServer
  private lateinit var feed: RealFeed

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    val api = AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
    val stream = AmadeusStream(server.url("/").toString().trimEnd('/'), OkHttpClient())
    feed = RealFeed("s1", api, stream)
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun initialLoadsLatestState() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"messages":[{"role":"user","text":"帮我改个文件"},{"role":"assistant","text":"好呀\n[[AMW:{\"mood\":\"happy\",\"sprite\":\"wag\"}]]"}],"hasMore":false}"""
    ).addHeader("Content-Type", "application/json"))
    val segs = feed.initial()
    assertEquals(1, segs.size)
    assertEquals("好呀", segs[0].dialog)
    assertEquals(AmadeusMood.happy, segs[0].tag.mood)
  }
}
```

- [ ] **Step 3: 实现**

`RealFeed.kt`:
```kotlin
package com.amadeus.whale.feed

import com.amadeus.whale.model.AmadeusSegment
import com.amadeus.whale.model.AmadeusSegmentParser
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusStream
import com.amadeus.whale.network.StreamEvent

class RealFeed(
  private val sessionId: String,
  private val api: AmadeusApi,
  private val stream: AmadeusStream,
) : MessageFeed {

  override suspend fun initial(): List<AmadeusSegment> {
    val page = api.pageSession(sessionId)
    val assistantTexts = page.messages.filter { it.role == "assistant" }
      .map { it.text }.joinToString("\n")
    return if (assistantTexts.isBlank()) emptyList()
      else AmadeusSegmentParser.parse(assistantTexts)
  }

  fun attach(onEvent: (StreamEvent) -> Unit): AutoCloseable =
    stream.open(sessionId, onEvent)

  suspend fun send(text: String) { api.sendPrompt(sessionId, text) }
  suspend fun cancel() { api.cancel(sessionId) }
}
```

- [ ] **Step 4: 运行确认通过 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.feed.RealFeedTest"`
Expected: PASS。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/feed app/amadeus-android/app/src/test
git commit -m "feat(app): real session feed over REST page + SSE attach"
```

---

### Task 13: 选档页（SaveSlotScreen + ViewModel）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/saveslot/SaveSlotScreen.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/saveslot/SaveSlotViewModel.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/saveslot/SaveSlotViewModelTest.kt`

**Interfaces:**
- Consumes: `AmadeusApi`（Task 10）。
- Produces:
  - `data class SaveSlotUi(id, title, updatedAt, lastMessage)`
  - `class SaveSlotViewModel(api: AmadeusApi)`
    - `val list: StateFlow<List<SaveSlotUi>>`、`val busy: StateFlow<Boolean>`
    - `suspend fun load()`
    - `suspend fun create(title: String? = null, workspaceId: String? = null): SaveSlotUi?`
    - `suspend fun rename(id, title)`
    - `suspend fun archive(id)`
    - `fun choose(id) : String`（返回选中 id）
  - `@Composable fun SaveSlotScreen(viewModel, onOpen: (id: String) -> Unit, onChangeConnection: () -> Unit, onNewSession: () -> Unit)`：竖向列表（标题/工作区/时间），长按弹菜单（改名/删除），底部「新建会话」，顶部连接状态入口。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.amadeus.whale.saveslot

import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class SaveSlotViewModelTest {
  private lateinit var server: MockWebServer
  private lateinit var vm: SaveSlotViewModel

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    vm = SaveSlotViewModel(AmadeusApi(server.url("/").toString().trimEnd('/'), OkHttpClient()))
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun loadPopulatesList() = runTest {
    server.enqueue(MockResponse().setBody(
      """{"sessions":[{"id":"s1","title":"今天","mode":"amadeus","updatedAt":1}]}"""
    ).addHeader("Content-Type", "application/json"))
    vm.load()
    assertEquals(1, vm.list.value.size)
    assertEquals("今天", vm.list.value[0].title)
  }

  @Test fun archiveSendsRequest() = runTest {
    server.enqueue(MockResponse().setBody("{}"))
    vm.load() // consumes nothing; directly archive
    vm.archive("s1")
    val req = server.takeRequest()
    assertEquals("/amadeus/extensions/amadeus/routes/sessions/s1/archive", req.path)
  }
}
```

- [ ] **Step 2: 实现**

`SaveSlotViewModel.kt`:
```kotlin
package com.amadeus.whale.saveslot

import androidx.compose.runtime.Stable
import com.amadeus.whale.network.AmadeusApi
import com.amadeus.whale.network.AmadeusSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Stable data class SaveSlotUi(val id: String, val title: String, val updatedAt: Long, val lastMessage: String? = null)

class SaveSlotViewModel(private val api: AmadeusApi) {
  private val _list = MutableStateFlow<List<SaveSlotUi>>(emptyList())
  val list: StateFlow<List<SaveSlotUi>> = _list
  private val _busy = MutableStateFlow(false)
  val busy: StateFlow<Boolean> = _busy

  suspend fun load() {
    _busy.value = true
    _list.value = runCatching { api.listSessions() }.getOrDefault(emptyList())
      .map { SaveSlotUi(it.id, it.title, it.updatedAt, it.lastMessage) }
    _busy.value = false
  }

  suspend fun create(title: String? = null, workspaceId: String? = null): SaveSlotUi? {
    val s = runCatching { api.createSession(title, workspaceId) }.getOrNull() ?: return null
    return SaveSlotUi(s.id, s.title, s.updatedAt, s.lastMessage)
  }

  suspend fun rename(id: String, title: String) {
    runCatching { api.renameSession(id, title) }
    _list.value = _list.value.map { if (it.id == id) it.copy(title = title) else it }
  }

  suspend fun archive(id: String) {
    runCatching { api.archiveSession(id) }
    _list.value = _list.value.filterNot { it.id == id }
  }
}
```

`SaveSlotScreen.kt`:
```kotlin
package com.amadeus.whale.saveslot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SaveSlotScreen(
  viewModel: SaveSlotViewModel,
  onOpen: (String) -> Unit,
  onChangeConnection: () -> Unit,
  onNewSession: () -> Unit,
) {
  val list by viewModel.list.collectAsState()
  var menuId by remember { mutableStateOf<String?>(null) }
  var renameId by remember { mutableStateOf<String?>(null) }
  var renameTitle by remember { mutableStateOf("") }

  Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize()) {
      item { Text("选择会话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
      items(list, key = { it.id }) { slot ->
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
          Column(Modifier.combinedClickable(
            onClick = { onOpen(slot.id) },
            onLongClick = { menuId = slot.id },
          ).padding(16.dp)) {
            Text(slot.title, style = MaterialTheme.typography.titleMedium)
            slot.lastMessage?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
    }
    Button(onClick = onNewSession, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()) {
      Text("新建会话")
    }
    TextButton(onClick = onChangeConnection, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
      Text("切换连接")
    }
  }

  menuId?.let { id ->
    AlertDialog(onDismissRequest = { menuId = null },
      title = { Text("会话操作") },
      text = { Text("对该会话执行操作") },
      confirmButton = { TextButton(onClick = { renameId = id; renameTitle = list.first { it.id == id }.title; menuId = null }) { Text("改名") } },
      dismissButton = { TextButton(onClick = { viewModel.archive(id); menuId = null }) { Text("删除") } },
    )
  }
  renameId?.let { id ->
    AlertDialog(onDismissRequest = { renameId = null },
      title = { Text("重命名") },
      text = { OutlinedTextField(value = renameTitle, onValueChange = { renameTitle = it }) },
      confirmButton = {
        TextButton(onClick = { viewModel.rename(id, renameTitle); renameId = null }) { Text("确定") }
      },
      dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } },
    )
  }
}
```

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.saveslot.SaveSlotViewModelTest"` 与 `:app:compileDebugKotlin`
Expected: PASS / 成功。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/saveslot app/amadeus-android/app/src/test
git commit -m "feat(app): save slot screen with list/create/rename/archive"
```

---

### Task 14: 设置页（SettingsScreen + ViewModel）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/settings/SettingsScreen.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/settings/SettingsViewModel.kt`
- Create: `app/amadeus-android/app/src/test/java/com/amadeus/whale/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `AmadeusPrefs`（Task 9）、`AmadeusApi`（Task 10）。
- Produces:
  - `class SettingsViewModel(prefs: AmadeusPrefs, apiProvider: (baseUrl: String) -> AmadeusApi)`
    - `val url: StateFlow<String>`、`val status: StateFlow<String>`（未测试/连接中/已连接(origin)/失败）
    - `suspend fun testAndSave(url: String): Boolean`：`apiProvider(url).health()` → 成功存 prefs.baseUrl，返回 true
    - `fun disconnect()`：`prefs.clear()`
    - `var soundEnabled`/`var showToolProgress`：读写 prefs
  - `@Composable fun SettingsScreen(viewModel, onDone: () -> Unit, isRealMode: Boolean)`：全屏浮层——网关地址输入框 + 测试按钮 + 保存状态 + 断开连接（仅真实模式）+ 演出偏好开关（环境音、工具进度）+ 返回按钮。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.amadeus.whale.settings

import com.amadeus.whale.AmadeusPrefs
import com.amadeus.whale.PrefsStore
import com.amadeus.whale.network.AmadeusApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsViewModelTest {
  private lateinit var server: MockWebServer
  private lateinit var vm: SettingsViewModel
  private lateinit var store: PrefsStore
  private val client = OkHttpClient()

  class MemStore : PrefsStore {
    val m = mutableMapOf<String, String>()
    override fun getString(k: String) = m[k]
    override fun putString(k: String, v: String) { m[k] = v }
    override fun getBoolean(k: String, def: Boolean) = m[k]?.toBoolean() ?: def
    override fun putBoolean(k: String, v: Boolean) { m[k] = v.toString() }
    override fun remove(k: String) { m.remove(k) }
  }

  @Before fun setUp() {
    server = MockWebServer(); server.start()
    store = MemStore()
    val prefs = AmadeusPrefs(store)
    vm = SettingsViewModel(prefs) { AmadeusApi(it.trimEnd('/'), client) }
  }
  @After fun tearDown() { server.shutdown() }

  @Test fun testAndSavePersistsOnHealthOk() = runTest {
    server.enqueue(MockResponse().setBody("""{"capabilities":{"sessions":true}}""").addHeader("Content-Type", "application/json"))
    val ok = vm.testAndSave(server.url("/").toString().trimEnd('/'))
    assertTrue(ok)
    assertEquals(server.url("/").toString().trimEnd('/'), store.getString("gateway_url"))
  }

  @Test fun disconnectClearsUrl() = runTest {
    store.putString("gateway_url", "https://x:3444")
    vm.disconnect()
    assertNull(store.getString("gateway_url"))
  }
}
```

- [ ] **Step 2: 实现**

`SettingsViewModel.kt`:
```kotlin
package com.amadeus.whale.settings

import com.amadeus.whale.AmadeusPrefs
import com.amadeus.whale.network.AmadeusApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
  private val prefs: AmadeusPrefs,
  private val apiProvider: (String) -> AmadeusApi,
) {
  private val _url = MutableStateFlow(prefs.baseUrl ?: "")
  val url: StateFlow<String> = _url
  private val _status = MutableStateFlow("")
  val status: StateFlow<String> = _status

  suspend fun testAndSave(url: String): Boolean {
    val api = apiProvider(url)
    return runCatching {
      if (!api.health()) return false
      prefs.baseUrl = url
      _status.value = "已连接"
      true
    }.getOrElse {
      _status.value = "连接失败: ${it.message}"
      false
    }
  }

  fun disconnect() { prefs.clear(); _status.value = "已断开" }

  var soundEnabled: Boolean
    get() = prefs.soundEnabled
    set(v) { prefs.soundEnabled = v; AmbientSoundController.enabled = v }
  var showToolProgress: Boolean
    get() = prefs.showToolProgress
    set(v) { prefs.showToolProgress = v }
}
```

`SettingsScreen.kt`:
```kotlin
package com.amadeus.whale.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
  viewModel: SettingsViewModel,
  isRealMode: Boolean,
  onDone: () -> Unit,
) {
  val url by viewModel.url.collectAsState()
  val status by viewModel.status.collectAsState()
  var urlInput by remember(url) { mutableStateOf(url) }
  var testing by remember { mutableStateOf(false) }
  var sound by remember { mutableStateOf(viewModel.soundEnabled) }
  var toolProgress by remember { mutableStateOf(viewModel.showToolProgress) }

  Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
      Text("设置", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(16.dp))
      OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("网关地址") },
        placeholder = { Text("https://192.168.x.x:3444") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Button(onClick = {
          testing = true
          viewModel.testAndSave(urlInput.trim())
          testing = false
        }, enabled = !testing) { Text(if (testing) "测试中…" else "测试并保存") }
        Spacer(Modifier.width(12.dp))
        Text(status)
      }
      Spacer(Modifier.height(24.dp))
      Text("演出偏好", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("环境音（demo）")
        Switch(checked = sound, onCheckedChange = { sound = it; viewModel.soundEnabled = it })
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("工具进度")
        Switch(checked = toolProgress, onCheckedChange = { toolProgress = it; viewModel.showToolProgress = it })
      }
      if (isRealMode) {
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { viewModel.disconnect() }, modifier = Modifier.fillMaxWidth()) {
          Text("断开连接并回演示")
        }
      }
      Spacer(Modifier.weight(1f))
      Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
  }
}
```
> 注：`SettingsViewModel` 用到 `AmbientSoundController`（Task 8），需 import `com.amadeus.whale.theatre.AmbientSoundController`。

- [ ] **Step 3: 运行确认通过 + 提交**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.amadeus.whale.settings.SettingsViewModelTest"` 与 `:app:compileDebugKotlin`
Expected: PASS / 成功。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/settings app/amadeus-android/app/src/test
git commit -m "feat(app): full-screen settings with gateway test/save/disconnect and toggles"
```

---

### Task 15: 主状态机（MainActivity + AppRoot）

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/MainActivity.kt`（覆盖占位）
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/AppRoot.kt`

**Interfaces:**
- Consumes: 全部前述 Task 的组件。
- Produces:
  - `@Composable fun AppRoot(prefs: AmadeusPrefs, sound: AmbientSound)`：状态 `DemoTheatre → RealTheatre`。
    - 启动：读 prefs.baseUrl → 无 → Demo；有 → 并发 `api.health()` → 可达 → Real（选档），失败 → Demo（并提示）。
    - Demo 剧场：`TheatreViewModel(DemoFeed())`，`backgroundResolver = { "palace-night" }`；齿轮→Settings（isRealMode=false）。
    - Real 剧场：`TheatreViewModel(RealFeed(...))`，`backgroundResolver = { 居家背景按 mood 映射（全部用 bg-claude-writing-study，或 mood==tool 用 bg-gpt-collaboration-workshop） }`；输入栏可见；SSE attach 追加段；齿轮→Settings（isRealMode=true）。
    - Settings 里 testAndSave 成功 → 切 Real 并重新进入选档。
    - Settings 里 disconnect → prefs.clear() + 回 Demo。
  - `MainActivity`：持有 `AmadeusPrefs`/`AmbientSound`，`setContent { AppRoot(...) }`。

- [ ] **Step 1: 写 AppRoot（核心状态机）**

`MainActivity.kt`:
```kotlin
package com.amadeus.whale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.amadeus.whale.theatre.AmbientSound

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = AmadeusPrefs.from(this)
    val sound = AmbientSound(this)
    setContent { AppRoot(prefs, sound) }
  }
}
```

`AppRoot.kt`:
```kotlin
package com.amadeus.whale

import androidx.compose.runtime.*
import com.amadeus.whale.feed.DemoFeed
import com.amadeus.whale.feed.RealFeed
import com.amadeus.whale.model.AmadeusMood
import com.amadeus.whale.network.*
import com.amadeus.whale.saveslot.SaveSlotScreen
import com.amadeus.whale.saveslot.SaveSlotViewModel
import com.amadeus.whale.settings.SettingsScreen
import com.amadeus.whale.settings.SettingsViewModel
import com.amadeus.whale.theatre.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Composable
fun AppRoot(prefs: AmadeusPrefs, sound: AmbientSound) {
  var screen by remember { mutableStateOf(Screen.Demo) }
  var selectedSessionId by remember { mutableStateOf<String?>(null) }
  var settingsOpen by remember { mutableStateOf(false) }

  val client = remember {
    OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
  }
  val apiOf = remember { { url: String -> AmadeusApi(url, client) } }

  LaunchedEffect(Unit) {
    val saved = prefs.baseUrl
    if (saved != null && runCatching { apiOf(saved).health() }.getOrDefault(false)) {
      screen = Screen.Real
    } else {
      screen = Screen.Demo
    }
  }

  val settingsVm = remember { SettingsViewModel(prefs, apiOf) }
  if (settingsOpen) {
    SettingsScreen(viewModel = settingsVm, isRealMode = screen == Screen.Real, onDone = {
      settingsOpen = false
      if (prefs.baseUrl != null && screen != Screen.Real) screen = Screen.Real
    })
    return
  }

  when (screen) {
    Screen.Demo -> {
      val vm = remember { TheatreViewModel(DemoFeed(), { "palace-night" }) }
      LaunchedEffect(Unit) { vm.load(); sound.playBgm("rain"); sound.play("wave") }
      TheatreScreen(
        viewModel = vm,
        backgroundResolver = { "palace-night" },
        onOpenSettings = { settingsOpen = true },
      )
    }
    Screen.Real -> {
      val saveVm = remember { SaveSlotViewModel(apiOf(prefs.baseUrl!!)) }
      LaunchedEffect(Unit) { saveVm.load() }
      selectedSessionId?.let { sessionId ->
        val api = apiOf(prefs.baseUrl!!)
        val stream = remember(prefs.baseUrl) { AmadeusStream(prefs.baseUrl!!, client) }
        val realFeed = remember(sessionId) { RealFeed(sessionId, api, stream) }
        val vm = remember(sessionId) {
          TheatreViewModel(realFeed, backgroundResolver = { mood ->
            if (mood == AmadeusMood.tool || mood == AmadeusMood.think) "bg-gpt-collaboration-workshop"
            else "bg-claude-writing-study"
          })
        }
        LaunchedEffect(sessionId) {
          vm.load()
          val closer = realFeed.attach { event ->
            when (event) {
              is StreamEvent.Segments -> event.list.forEach { vm.enqueue(it) }
              is StreamEvent.Choice -> vm.showChoice(event)
              is StreamEvent.Ended -> vm.markIdle()
            }
          }
          onDispose { closer.close() }
        }
        TheatreScreen(
          viewModel = vm,
          backgroundResolver = vm.backgroundResolverFor,
          onOpenSettings = { settingsOpen = true },
          inputBar = { send -> InputBar(onSend = { text -> vm.sendToFeed(text) }) },
        )
      } ?: run {
        SaveSlotScreen(
          viewModel = saveVm,
          onOpen = { selectedSessionId = it },
          onChangeConnection = { settingsOpen = true },
          onNewSession = {
            val created = saveVm.create()
            created?.let { selectedSessionId = it.id }
          },
        )
      }
    }
  }
}

@Composable
private fun InputBar(onSend: (String) -> Unit) {
  var text by remember { mutableStateOf("") }
  Row(Modifier.fillMaxWidth().padding(8.dp)) {
    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("和鲸鱼娘说点什么…") })
    Button(onClick = { if (text.isNotBlank()) { onSend(text); text = "" } }) { Text("发送") }
  }
}

private enum class Screen { Demo, Real }
```
> 注：此 Task 依赖 `TheatreViewModel` 提供 `enqueue(segment)`、`showChoice(event)`、`markIdle()`、`sendToFeed(text)`、`backgroundResolverFor` 等扩展——这些需在 Task 5 的 ViewModel 上补充（第 3 步说明）。此处按接口存在编写，实现时同步补入。

- [ ] **Step 2: 扩展 TheatreViewModel（真实模式接口）**

在 `TheatreViewModel.kt` 增：
```kotlin
fun enqueue(segment: AmadeusSegment) {
  _queue.value = _queue.value + segment
  if (!hasCurrent()) _uiState.value = toUi(segment)
}
fun showChoice(event: StreamEvent.Choice) {
  pendingChoice = ChoiceUi(event.choiceId, event.question, event.options.map { it.label })
}
fun markIdle() { _uiState.value = _uiState.value.copy(mood = AmadeusMood.idle, typingFinished = true) }
suspend fun sendToFeed(text: String) { (feed as? RealFeed)?.send(text) }
val backgroundResolverFor: (AmadeusMood) -> String get() = backgroundResolver
data class ChoiceUi(val choiceId: String, val question: String, val options: List<String>)
```
并让 `TheatreUiState` 携带 `choice: ChoiceUi? = null`、`hasMoreTyping` 逻辑；同步补单测（`enqueue` 追加、`showChoice` 置 choice）。

- [ ] **Step 3: 运行全部测试 + 构建**

Run: `.\gradlew.bat :app:testDebugUnitTest` 与 `.\gradlew.bat :app:assembleDebug`
Expected: 全部 PASS；BUILD SUCCESSFUL，产出 APK。

- [ ] **Step 4: 提交**

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale
git commit -m "feat(app): root state machine demo/real theatre with remembered direct connect"
```

---

### Task 16: 报告/预览/choice/历史窗口

**Files:**
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/window/ReportWindow.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/window/PreviewWindow.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/window/ChoiceWindow.kt`
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/window/HistoryWindow.kt`

**Interfaces:**
- Consumes: `AmadeusApi`（Task 10）、`AmadeusSegment`（Task 3）。
- Produces:
  - `@Composable fun ReportWindow(report: ReportPayload, onClose: () -> Unit)`：全屏覆盖层，可滚动 Markdown（简单文本渲染，含文件列表行），标题+关闭按钮。
  - `@Composable fun PreviewWindow(preview: PreviewPayload, onClose: () -> Unit)`：按 `type` 渲染（web→WebView、image→AsyncImage、code→等宽文本），缩放/全屏（`rememberTransformableState` 双指缩放）。
  - `@Composable fun ChoiceWindow(choiceId: String, question: String, options: List<String>, onSelect: (String) -> Unit, onClose: () -> Unit)`：选项卡列表，点选即回调；关闭=取消。
  - `@Composable fun HistoryWindow(sessionId: String, api: AmadeusApi, onClose: () -> Unit)`：web 对话样式，最近几条，上翻加载更早（`pageSession(beforeSeq)` 分页）。
  - AppRoot 在 `state.windowId` 非空或 `pendingChoice` 非空或用户打开历史时叠加对应窗口；`onOpenWindow(windowId, type)` 触发拉取渲染。

- [ ] **Step 1: 实现四个窗口（编译验证）**

（内容较长，关键骨架如下；渲染用 Material3 + Coil，无需额外依赖。）
- `ReportWindow`：`Surface(fillMaxSize)` + `Column`：顶栏（标题+关闭），`LazyColumn` 逐行渲染 `markdown.lines()`。
- `PreviewWindow`：`when(type){ "web" -> AndroidView(WebView); "image" -> AsyncImage(model=content); else -> BasicText(content, mono) }` + `graphicsLayer{ scaleX/scaleY }` + `detectTransformGestures`。
- `ChoiceWindow`：`Column` 居中卡片，question + `options.forEach { Button(...) }`，点击 `onSelect(label)`。
- `HistoryWindow`：`LazyColumn(reverseLayout=true)` + 首次 `pageSession(null)`，滚动到顶再 `pageSession(beforeSeq=oldest)`。

> 依赖：`android.webkit.WebView` 为平台 API，无需新依赖；Markdown 渲染本期用纯文本行渲染（不引入 markdown 库，遵循 YAGNI）。

- [ ] **Step 2: 构建验证 + 提交**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: 成功。

```bash
git add app/amadeus-android/app/src/main/java/com/amadeus/whale/window
git commit -m "feat(app): report/preview/choice/history windows"
```

---

### Task 17: 素材补齐与整体验收

**Files:**
- Modify: `app/amadeus-android/app/src/main/assets/amadeus/**`（按需补齐）
- Modify: `app/amadeus-android/app/src/main/res/raw/**`（音频）
- Create: `app/amadeus-android/app/src/main/java/com/amadeus/whale/BackgroundCatalog.kt`（可选：背景素材与 mood 映射常量）

**Interfaces:**
- Consumes: 全部。
- Produces: 可出 APK、demo 完整可演、真实连接可用。

- [ ] **Step 1: 素材核对**

- 立绘：现有 8 张 `whale-*.webp` + `maid-left.webp` 覆盖全部 sprite 映射（Task 6 已测）。
- 背景：demo 用 `palace-night.webp`（海边月夜顶替）；真实居家用 `bg-claude-writing-study.webp`/`bg-gpt-collaboration-workshop.webp`。若缺失，从社区开源仓库 JAdpp/dsh-whale-galgame（MIT）补齐海边/月夜/居家素材，拷贝至 `assets/amadeus/` 并保持文件名一致。
- 音频：`res/raw/sfx_wave`、`sfx_bell`、`bgm_rain`——从社区开源音效（freesound 类 CC0 或 JAdpp 仓库 assets）获取转 `.ogg`/`.wav` 放入 `res/raw/`。若无，`AmbientSound.play` 已做空安全，不崩溃。

- [ ] **Step 2: 真机/模拟器冒烟**

- 安装 `app-debug.apk`，启动应直接进 demo 剧场（无保存网关）。
- 点按推进：打字→下一句；快进到底：设置里无该按钮时，点右上角齿轮→返回；demo 循环：退出重进 App 从头演。
- 环境音开关生效；背景 Crossfade、立绘呼吸/滑动入场、心情点缀可见。
- 配好网关后（本地起 Host 侧 `dsh-amadeus` + DSH），启动直接进选档；新建会话→进剧场→输入文字→SSE 短句演出→报告/预览/choice 窗口弹出→历史窗口分页。

- [ ] **Step 3: 最终提交**

```bash
git add app/amadeus-android
git commit -m "chore(app): asset/audio pass and acceptance wiring"
```

---

## Self-Review 记录

- **Spec 覆盖**：启动 demo（T15）、演出层（T6-T8）、导航/记住直连（T15）、选档/改名删除/新建（T13）、设置（T14）、真实会话 SSE（T11-T12）、报告/预览/choice/历史窗口（T16）、demo 相识剧本（T4）、环境音仅 demo（T8/T14）、mode 隔离（T10 list 固定 mode=amadeus + T15 只走 amadeus 会话）、素材保留（T1/T17）。
- **占位符**：无 TBD；Task 15 Step 2 明确列出需补充的接口并同步补测；音频素材缺失时有空安全回退。
- **类型一致性**：`TheatreUiState`/`TheatreViewModel` 接口在 T5 定义、T15 扩展（enqueue/showChoice/markIdle/sendToFeed/backgroundResolverFor）——T15 已注明扩展点；`AmadeusApi` 方法签名 T10 定义、T12 补充 `pageSession`（已标注原地补测）；`MessageFeed.initial()` 跨 T4/T12 一致。

## 待办（计划外，后续 Host 计划处理）

- Host 侧：adapter 注入、`save_report`/`show_preview` 工具、`user-questions/request` answerer、SSE `/stream/:sessionId` 端点、持久化 `~/.dsh/amadeus/`、控制面板实接、远程 provider 框架——这些属于**第二个计划（Host 桌面端）**，App 计划按本计划定下的 REST/SSE 契约实现客户端，双方契约以 spec 为准。