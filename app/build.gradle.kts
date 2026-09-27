plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.saeki.altimeter"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.saeki.altimeter"
        minSdk = 29          // Android 10 以上
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 個人利用向け: デバッグ鍵で署名（ストア公開時は正式な鍵に変更）
            signingConfig = signingConfigs.getByName("debug")
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
