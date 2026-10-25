plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val build = (System.getenv("WG_BUILD") ?: "1").toInt()

android {
    namespace = "com.ivyea.weiguang"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ivyea.weiguang"
        minSdk = 26
        targetSdk = 35
        versionCode = build
        versionName = "0.1.0-dev.$build"
    }

    // 正式签名只在 CI 里由仓库密钥还原（ANDROID_KEYSTORE_B64 / ANDROID_KEYSTORE_PASSWORD）
    signingConfigs {
        create("release") {
            val ks = System.getenv("WG_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("WG_KEYSTORE_PASSWORD")
                keyAlias = "weiguang"
                keyPassword = System.getenv("WG_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("WG_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.palette:palette:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
