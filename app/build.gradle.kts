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
        versionCode = 4
        versionName = "1.3"
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
    // Tesseract 5 OCR (Arabic and English; offline models bundled in APK)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
