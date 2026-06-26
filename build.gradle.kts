plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}
android {
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Signal Protocol — crypto
    implementation("org.signal:libsignal-android:0.67.0")
    implementation("org.signal:libsignal-client:0.67.0")

    // UI
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Stockage local
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // QR Code — génération
    implementation("com.google.zxing:core:3.5.3")

    // QR Code — scan caméra
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}