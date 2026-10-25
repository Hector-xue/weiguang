plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ivyea.weiguang.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ivyea.weiguang.probe"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("PROBE_BUILD") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("PROBE_BUILD") ?: "1")
    }

    // 验证工具专用的固定签名（不是正式 App 的），保证新版能直接覆盖安装
    signingConfigs {
        create("probe") {
            storeFile = rootProject.file("probe.keystore")
            storePassword = "android"
            keyAlias = "probe"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("probe")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // 只为测「浏览服务搜索」：系统自带的 MediaBrowser 没有 search()
    implementation("androidx.media:media:1.7.0")
}
