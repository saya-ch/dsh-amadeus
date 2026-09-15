import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

/*
 * 签名凭据来源（按优先级）：
 *   1. keystore.properties   —— 本地开发（已被 .gitignore 忽略）
 *   2. 环境变量               —— CI（GitHub Actions Secrets）
 * 两者都缺失时 release 构建产出未签名 APK，不阻断 debug 构建。
 */
val keystoreProperties = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, environment: String): String? =
  keystoreProperties.getProperty(property) ?: System.getenv(environment)

val releaseStoreFile = signingValue("storeFile", "AMADEUS_KEYSTORE_FILE")

android {
  namespace = "com.amadeus.whale"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.amadeus.whale"
    minSdk = 29
    targetSdk = 36
    // CI 按 tag 覆盖；默认值 = x.y.z → x*10000 + y*100 + z（0.1.0 → 100）
    versionCode = System.getenv("AMADEUS_VERSION_CODE")?.toIntOrNull() ?: 100
    versionName = System.getenv("AMADEUS_VERSION_NAME") ?: "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  signingConfigs {
    if (releaseStoreFile != null) {
      create("release") {
        storeFile = rootProject.file(releaseStoreFile)
        storePassword = signingValue("storePassword", "AMADEUS_KEYSTORE_PASSWORD")
        keyAlias = signingValue("keyAlias", "AMADEUS_KEY_ALIAS")
        keyPassword = signingValue("keyPassword", "AMADEUS_KEY_PASSWORD")
      }
    }
  }
  buildTypes {
    release {
      if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
      // Demo 阶段关闭 R8：避免 Compose / kotlinx-serialization 反射被裁掉
      isMinifyEnabled = false
      isShrinkResources = false
    }
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true }
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
  implementation(libs.zxing.core)
  implementation(libs.datastore.preferences)
  implementation(libs.camera.core)
  implementation(libs.camera.camera2)
  implementation(libs.camera.lifecycle)
  implementation(libs.camera.view)
  debugImplementation(libs.compose.ui.tooling)
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.bcprov)
  testImplementation(libs.bcpkix)
}
