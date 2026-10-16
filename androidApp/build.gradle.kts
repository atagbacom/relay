import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Signal Protocol — crypto
    // libsignal-android tire libsignal-client (API Java) ; les tests JVM
    // utilisent les bibliothèques natives pour ordinateur de ce dernier
    implementation("org.signal:libsignal-android:0.67.0")
    testImplementation("org.signal:libsignal-client:0.67.0")

    // UI
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Stockage local
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

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

android {
    namespace = "com.example.relay"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        // Clé de débogage versionnée (non secrète) : tous les APK de la CI
        // ont la même signature et s'installent par-dessus la version précédente
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    defaultConfig {
        // Identifiant définitif : le changer après une distribution empêcherait les mises à jour
        applicationId = "com.atagba.relay"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Bibliothèques natives pour ordinateur (Linux, macOS, Windows)
            // embarquées par libsignal-client : inutiles sur un téléphone
            excludes += listOf("libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll")
        }
        jniLibs {
            // Variante de test de libsignal, ~50 Mo par architecture
            excludes += "**/libsignal_jni_testing.so"
            // x86 = émulateurs : retiré aussi de l'APK universel, que le
            // découpage par architecture ne filtre pas
            // (gardé pour le test de démarrage sur émulateur en CI : -Prelay.emulateur)
            if (!project.hasProperty("relay.emulateur")) {
                excludes += listOf("**/x86/*.so", "**/x86_64/*.so")
            }
            // Bibliothèques natives compressées dans l'APK : téléchargement
            // bien plus léger (au prix d'une extraction à l'installation)
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            // Téléphones réels uniquement (x86 = émulateurs) ; un APK par
            // architecture, plus un APK universel pour les deux
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            if (project.hasProperty("relay.emulateur")) include("x86_64")
            isUniversalApk = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        // exigé par libsignal-android (API Java récentes sur Android 8)
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}
