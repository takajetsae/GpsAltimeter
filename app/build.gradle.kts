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
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            // 個人利用向けの固定鍵（更新時も同じ署名になるようリポジトリに同梱）
            storeFile = file("release.jks")
            storePassword = "gpsalt123"
            keyAlias = "gpsalt"
            keyPassword = "gpsalt123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
