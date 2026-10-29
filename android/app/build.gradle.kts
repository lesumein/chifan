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
        versionCode = 8
        versionName = "0.8"
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

// 외부 라이브러리는 ML Kit 둘: 중국어 글자 인식(모델 내장)과 번역(모델은 한 번 내려받음). 둘 다 폰 안에서만 동작.
// 그 외는 Kotlin 표준 라이브러리 + Android 프레임워크.
dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 폰 안 번역(중→한). 모델(약 60MB)은 설정 화면에서 한 번 내려받고, 그 뒤엔 오프라인으로 돈다.
    implementation("com.google.mlkit:translate:17.0.3")
}
