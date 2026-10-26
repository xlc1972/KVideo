import java.io.File

plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}

val defaultKVideoUrl = providers
    .gradleProperty("kvideoUrl")
    .orElse("")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.kvideo.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.djjk.ziqidonglai.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "4.9.24"

        buildConfigField("String", "DEFAULT_KVIDEO_URL", "\"$defaultKVideoUrl\"")
    }

    signingConfigs {
        // 固定签名: 应用内自动更新要求新旧包签名一致, 否则系统拒绝安装。
        // 密钥通过 GitHub Secrets 注入 (ANDROID_KEYSTORE_BASE64 等)。
        create("tvRelease") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (storePath != null && storePath.isNotBlank() && File(storePath).exists()) {
                storeFile = File(storePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI 注入密钥时用固定签名; 本地无密钥时回退 debug 签名 (便于本地调试)
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("tvRelease")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // 不加 applicationIdSuffix: 必须与 release 同包名, 否则无法覆盖安装
            // 也不能用于应用内自更新 (签名/包名必须一致)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.webkit:webkit:1.9.0")

    // ---- Compose for TV: 原生电视墙 (焦点系统由系统管理, 不依赖 JS) ----
    // tv-material 1.0.1 -> Compose 1.6.8, 与 compileSdk 34 / Kotlin 1.9.22 兼容。
    // 注意: tv-material 1.1.0 需要 Compose 1.10 + Kotlin 2.1 + compileSdk 35, 暂不可用。
    // Compose Compiler 1.5.10 是 Kotlin 1.9.22 的官方配套版本 (1.5.15 需 Kotlin 1.9.25)。
    // 不用 kotlin("plugin.compose") 是因为那要求 Kotlin 2.0+。
    implementation("androidx.compose.compiler:compiler:1.5.10")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.tv:tv-material:1.0.1")
    // 注意: tv-foundation:1.0.0 依赖 Compose 1.10.3 + Kotlin 2.1, 会强制 compileSdk 35 + AGP 8.6。
    // 本项目只需 tv-material 的组件, 故不引入 tv-foundation。
    implementation("androidx.activity:activity-compose:1.8.2")

    // 图片加载 (海报)
    implementation("io.coil-kt:coil-compose:2.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 锁定 Compose 1.6.8: 避免被传递依赖 (activity-compose / coil) 拉高到 1.10+
    // 拉高会要求 compileSdk 35 + AGP 8.6, 本项目工具链是 AGP 8.2 / compileSdk 34。
    configurations.configureEach {
        resolutionStrategy {
            force(
                "androidx.compose:compose-bom:2024.06.00",
                "androidx.compose.compiler:compiler:1.5.10",
                "androidx.compose.ui:ui:1.6.8",
                "androidx.compose.ui:ui-graphics:1.6.8",
                "androidx.compose.ui:ui-text:1.6.8",
                "androidx.compose.ui:ui-util:1.6.8",
                "androidx.compose.ui:ui-unit:1.6.8",
                "androidx.compose.ui:ui-tooling:1.6.8",
                "androidx.compose.ui:ui-tooling-preview:1.6.8",
                "androidx.compose.foundation:foundation:1.6.8",
                "androidx.compose.foundation:foundation-layout:1.6.8",
                "androidx.compose.runtime:runtime:1.6.8",
                "androidx.compose.runtime:runtime-saveable:1.6.8",
                "androidx.compose.animation:animation:1.6.8",
                "androidx.compose.animation:animation-core:1.6.8",
                "androidx.compose.material:material-icons-core:1.6.8",
                "androidx.core:core:1.12.0",
                "androidx.core:core-ktx:1.12.0",
                "androidx.activity:activity-compose:1.8.2",
                "androidx.lifecycle:lifecycle-runtime-compose:2.7.0",
                "androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0",
                "androidx.transition:transition:1.5.0"
            )
        }
    }
}
