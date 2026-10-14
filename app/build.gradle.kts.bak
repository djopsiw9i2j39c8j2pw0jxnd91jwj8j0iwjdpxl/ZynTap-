plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Phiên bản -------------------------------------------------------------
// Lấy từ gradle.properties (appVersion) hoặc từ dòng lệnh: -PappVersion=1.00.02
val appVersionName: String = (project.findProperty("appVersion") as String?)?.trim().orEmpty()
    .ifEmpty { "1.00.01" }

// "1.00.01" -> 1_000_001 ; "1.02.10" -> 1_002_010 (luôn tăng khi version tăng)
fun versionCodeFrom(name: String): Int {
    val p = name.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    return p.getOrElse(0) { 0 } * 1_000_000 + p.getOrElse(1) { 0 } * 1_000 + p.getOrElse(2) { 0 }
}

android {
    namespace = "com.zyntap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zyntap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeFrom(appVersionName)
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    // Một khoá ký CỐ ĐỊNH cho mọi bản build -> các bản sau cài đè lên bản trước
    // được, không bị "xung đột gói" và không phải gỡ cài lại.
    signingConfigs {
        create("zyntap") {
            storeFile = file("zyntap-release.jks")
            storePassword = "zyntap2026"
            keyAlias = "zyntap"
            keyPassword = "zyntap2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("zyntap")
        }
        debug {
            signingConfig = signingConfigs.getByName("zyntap")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Không để cảnh báo lint làm hỏng việc build APK
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
}
