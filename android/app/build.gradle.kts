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
        versionCode = 2
        versionName = "0.2"
        // OCR 네이티브 라이브러리가 CPU 종류마다 10MB씩이라 arm64(2017년 이후 폰)만 넣는다
        ndk { abiFilters += listOf("arm64-v8a") }
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

// 외부 라이브러리는 OCR 하나뿐: ML Kit 중국어 글자 인식(모델이 앱에 포함, 폰 안에서만 동작).
// 그 외는 Kotlin 표준 라이브러리 + Android 프레임워크.
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
