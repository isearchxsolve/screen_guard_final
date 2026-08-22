plugins {
    // FIX: Removed version numbers. Inherit from root project.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safescreen.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safescreen.guard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            pickFirsts += listOf("**/libc++_shared.so", "**/libtensorflowlite_gpu_delegate.so")
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "**/*.kotlin_metadata")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ===== LiteRT 1.0.1 (Stable) =====
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    implementation("com.google.ai.edge.litert:litert-support:1.0.1")

    // ML Kit Object Detection
    implementation("com.google.mlkit:object-detection-custom:17.0.2")

    // Camera
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.compose.ui:ui-graphics:1.10.0")
}