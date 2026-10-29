plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.chifan"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.chifan"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // 사전은 레포의 data/*.json 그대로 쓴다 (웹앱과 한 곳에서 관리)
    sourceSets["main"].assets.srcDirs("../../data")

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 외부 라이브러리 없음: Kotlin 표준 라이브러리 + Android 프레임워크만 쓴다.
dependencies {}
