package com.safescreen.guard

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.safescreen.guard.ml.DetectionEngineManager
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
            val engine = DetectionEngineManager.getInstance(this)
            Log.i(TAG, "âœ”ï¸ DetectionEngine singleton initialized")
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Failed to initialize DetectionEngine", e)
        }

        // 2. PRE-WARM MLKIT MODEL
        // Forces MLKit to load the heavy native libraries now, instead of during the first frame capture.
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.i(TAG, "âš¡ Pre-warming MLKit TextRecognizer...")
                val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                // Process a tiny dummy image to trigger model load
                val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val image = InputImage.fromBitmap(dummy, 0)

                client.process(image)
                    .addOnSuccessListener {
                        Log.i(TAG, "âœ… MLKit Warmup Complete - Ready for recognition")
                    }
                    .addOnFailureListener {
                        Log.w(TAG, "âš ï¸ MLKit Warmup failed (will load on demand)")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error during pre-warming", e)
            }
        }
    }

    override fun onTerminate() {
        Log.i(TAG, "SafeScreen Application terminating")
        DetectionEngineManager.release()
        super.onTerminate()
    }
}
