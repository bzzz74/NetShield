plugins {
    id("com.android.application")
}

android {
    namespace = "com.qisi.netshield"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.qisi.netshield"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            // 用 debug 签名直接生成可安装的 release APK（便于 CI 产出成品）
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // libxposed 现代 API（LSPosed 2.x，API 100-102），仅编译期使用
    compileOnly("io.github.libxposed:api:102.0.0")
    // 模块 App 侧与 LSPosed 框架通信（RemotePreferences 配置共享）
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
