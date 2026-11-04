import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 本地密钥（local.properties 已 gitignore，不随仓库推送；缺失时用占位编译）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val deepseekKey = localProps.getProperty("deepseek_key") ?: "sk-LOCAL_KEY_PLACEHOLDER"

android {
    namespace = "com.xingyihui.interviewassistant.diag"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xingyihui.interviewassistant.diag"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Test E 讯飞实时转写：WebSocket 客户端（okhttp 4.x）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
