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
// 讯飞实时语音转写大模型(rtasr_llm) API Secret：新协议签名密钥（控制台-服务接口认证信息-APISecret）
val xfApiSecret = localProps.getProperty("xf_api_secret") ?: "XF_SECRET_PLACEHOLDER"

android {
    namespace = "com.xingyihui.interviewassistant.diag"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xingyihui.interviewassistant.diag"
        minSdk = 26
        targetSdk = 34
        versionCode = 27
        versionName = "3.5.0"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepseekKey\"")
        buildConfigField("String", "XF_API_SECRET", "\"$xfApiSecret\"")
        buildConfigField("String", "XF_APP_ID", "\"6eb32081\"")
        buildConfigField("String", "XF_API_KEY", "\"876f9549559a4592a7fc8195fbbe343b\"")
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
