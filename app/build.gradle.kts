plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName: String = (project.findProperty("appVersion") as String?)?.trim().orEmpty()
    .ifEmpty { "1.00.36" }

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

    val signingStorePassword = providers.gradleProperty("zynStorePassword").orNull ?: System.getenv("ZYNTAP_STORE_PASSWORD")
    val signingKeyPassword = providers.gradleProperty("zynKeyPassword").orNull ?: System.getenv("ZYNTAP_KEY_PASSWORD")
    val signingAlias = System.getenv("ZYNTAP_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "zyntap"
    val signingStore = file(System.getenv("ZYNTAP_KEYSTORE_PATH")?.takeIf { it.isNotBlank() } ?: "zyntap-release.jks")
    val hasReleaseKey = signingStore.exists() && !signingStorePassword.isNullOrEmpty() && !signingKeyPassword.isNullOrEmpty()
    val signingRequested = signingStore.exists() || !signingStorePassword.isNullOrEmpty() || !signingKeyPassword.isNullOrEmpty()
    check(!signingRequested || hasReleaseKey) { "Release signing is incomplete: provide the same keystore and both passwords." }
    signingConfigs {
        if (hasReleaseKey) create("zyntap") {
            storeFile = signingStore
            storePassword = signingStorePassword
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("zyntap") else null
        }
        debug {
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "zyntap" else "debug")
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
        
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        
        resources.excludes += "/META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        resources.excludes += "/META-INF/*.{SF,DSA,RSA,EC}"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
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

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
