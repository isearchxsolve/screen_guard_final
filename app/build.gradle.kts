plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safescreen.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safescreen.guard"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Keep only ARM builds → reduces APK size
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Required for TFLite & Whisper models
    aaptOptions {
        noCompress("tflite", "bin")
    }

    packaging {
        resources {
            pickFirsts += listOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_gpu_delegate.so",
                "**/libtensorflowlite_jni.so",
                "**/libwhisper.so",
                "**/libwhisper_vfpv4.so"
            )

            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "**/*.kotlin_metadata"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // Android core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // === TensorFlow Lite ===
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Enables FlexDelegate ops (supports advanced YOLO models)
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    // === MLKit (Text Detection) ===
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Object detection (not used but safe to keep)
    implementation("com.google.mlkit:object-detection:17.0.0")

    // === MediaPipe Audio ===
    implementation("com.google.mediapipe:tasks-audio:0.10.14")

    // === Whisper Offline ASR ===
    implementation("io.github.givimad:whisper-jni:1.7.1")

    // Optional fallback speech engine
    // implementation("com.alphacephei:vosk-android:0.3.47")
}
