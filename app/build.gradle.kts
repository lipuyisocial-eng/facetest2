plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xingyihui.interviewassistant.diag"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xingyihui.interviewassistant.diag"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
