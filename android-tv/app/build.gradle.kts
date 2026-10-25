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
        versionCode = 21
        versionName = "4.9.21"

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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.webkit:webkit:1.9.0")
}
