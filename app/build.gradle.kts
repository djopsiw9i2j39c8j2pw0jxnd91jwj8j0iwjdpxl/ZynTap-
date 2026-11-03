import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun loadProps(path: String) = Properties().apply { rootProject.file(path).reader().use { load(it) } }

val versionProps = loadProps("version.properties")
val keystoreProps = loadProps("keystore/keystore.properties")

// -PversionName / -PversionCode (CI) ghi đè version.properties
fun version(key: String, gradleProp: String): String =
    providers.gradleProperty(gradleProp).orNull ?: versionProps.getProperty(key)

// Biến môi trường NULIZ_* (CI secrets) ghi đè keystore.properties
fun signing(key: String): String = System.getenv("NULIZ_$key") ?: keystoreProps.getProperty(key)

android {
    namespace = "com.nuliz.ads"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nuliz.ads"
        minSdk = 26
        targetSdk = 34
        versionCode = version("VERSION_CODE", "versionCode").toInt()
        versionName = version("VERSION_NAME", "versionName")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signing("STORE_FILE"))
            storePassword = signing("STORE_PASSWORD")
            keyAlias = signing("KEY_ALIAS")
            keyPassword = signing("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
