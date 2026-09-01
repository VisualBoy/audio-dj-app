import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Dev-only config from local.properties (gitignored) — keeps the LAN IP out of the public repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.audiodj.capture"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.audiodj.capture"
        minSdk = 29          // AudioPlaybackCapture requires API 29 (Android 10)
        targetSdk = 36       // Gate 2.5: exercise Android 16 target-API behavior changes
        versionCode = 1
        versionName = "0.1-spike"
        buildConfigField("String", "DEV_TOKEN_API", "\"${localProps.getProperty("dev.token.api") ?: ""}\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Native WebRTC bindings
    implementation("io.getstream:stream-webrtc-android:1.2.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
