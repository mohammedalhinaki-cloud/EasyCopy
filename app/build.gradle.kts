plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.easycopy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easycopy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Bundled OCR: works offline without Google Play services on Huawei devices.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
