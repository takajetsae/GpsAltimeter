plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.gpsaltimeter.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.gpsaltimeter.app"
        minSdk = 29          // Android 10 以上
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 署名鍵はリポジトリに置かず、ビルド時に作る開発用の鍵で署名（個人利用向け）
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
