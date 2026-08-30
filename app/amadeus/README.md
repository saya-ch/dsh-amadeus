# Amadeus Whale APP — Jetpack Compose + Live2D Ready

原生 Galgame 剧场，不再是 WebView 薄壳。

## 架构

```
app/amadeus/
  ui/theatre/  — 剧场 (立绘 + 对话框)
    SpriteRenderer.kt      // 接口，Static vs Live2D
    TheatreScreen.kt       // Galgame 主舞台
  ui/saveslot/ — 选档页 (amadeus 会话列表)
  network/     — 复用 dsh-mobile 的 TLS/配对/发现 (Kotlin OkHttp)
  assets/      — sprites/backgrounds/ui (webp/psd)
```

## 渲染器

- `StaticSpriteRenderer` — Coil 加载 `whale_*.webp` + Crossfade (MVP)
- `Live2DSpriteRenderer` — 占位，未来接入 `Live2D Cubism SDK for Native` 的 GLSurfaceView

切换只需在 TheatreScreen 里换实现。

## 依赖 (app/build.gradle.kts 占位)

```kotlin
implementation("androidx.compose.ui:ui:1.7.0")
implementation("io.coil-kt:coil-compose:2.6.0")
implementation("com.airbnb.android:lottie-compose:6.1.0")
implementation("androidx.media3:media3-exoplayer:1.4.0")
// Live2D (未来启用)
// implementation(files("libs/live2d-cubism-sdk.aar"))
```

## 标签驱动

`[[AMW:{mood,sprite,voice,sfx,bgm}]]` 在 TheatreViewModel 中解析，驱动 renderer + 音效。
