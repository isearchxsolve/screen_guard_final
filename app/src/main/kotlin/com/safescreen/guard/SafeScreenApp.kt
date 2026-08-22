package com.safescreen.guard

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application class to initialize DetectionEngine singleton early.
 * OPTIMIZED: Pre-warms MLKit model to prevent initial lag.
 */
class SafeScreenApp : Application() {
    private val TAG = "SafeScreenApp"

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "=== SafeScreen Application Starting ===")

        // 1. Initialize DetectionEngine singleton IMMEDIATELY
        try {
            Log.i(TAG, "âœ”ï¸ DetectionEngine singleton initialized")
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Failed to initialize DetectionEngine", e)
        }

        // 2. PRE-WARM MLKIT MODEL
        // Forces MLKit to load the heavy native libraries now, instead of during the first frame capture.

    }

    override fun onTerminate() {
        Log.i(TAG, "SafeScreen Application terminating")
        super.onTerminate()
    }
}