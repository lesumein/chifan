import java.util.Properties

// 릴리즈 서명 정보 (android/keystore.properties, 커밋 안 함)
val ksProps = Properties().also { p ->
    val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { p.load(it) }
}

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
        versionCode = 29
        versionName = "0.29"
        // OCR 네이티브 라이브러리가 CPU 종류마다 10MB씩이라 arm64(2017년 이후 폰)만 넣는다
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 사전은 레포의 data/*.json 그대로 쓴다 (웹앱과 한 곳에서 관리)
    sourceSets["main"].assets.srcDirs("../../data")

    // 릴리즈 서명: 키와 비밀번호는 레포에 두지 않는다. android/keystore.properties(커밋 안 함)에
    //   storeFile=keystore/chifan-release.jks / storePassword=... / keyAlias=... / keyPassword=...
    // 가 있으면 그것으로 서명하고, 없으면 서명 없이 빌드된다.
    signingConfigs {
        create("release") {
            if (ksProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksProps.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    // 릴리즈 빌드 때 lint 도구를 따로 내려받는데, 빌드 환경에서 막힐 때가 있어 끈다 (검사는 단위 테스트·대조 테스트로)
    lint { checkReleaseBuilds = false; abortOnError = false }

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
    // 스크롤 상태 기계(FrameGate) 단위 테스트
    testImplementation("junit:junit:4.13.2")
    // 라벨러 대조 테스트(LabelerParityTest)에서 data 폴더 json 을 읽는 진짜 org.json (안드로이드 것은 테스트에서 빈 껍데기)
    testImplementation("org.json:json:20240303")
}
