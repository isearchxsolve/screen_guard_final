package com.safescreen.guard

import android.app.Application
import android.util.Log
import com.safescreen.guard.ml.DetectionEngineManager

/**
 * PHASE 2 FIX: Application class to initialize DetectionEngine singleton early
 * This ensures the singleton exists before any service tries to use it
 */
class SafeScreenApp : Application() {
    private val TAG = "SafeScreenApp"

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "=== SafeScreen Application Starting ===")
        Log.i(TAG, "  Package: $packageName")
        Log.i(TAG, "  Process: ${android.os.Process.myPid()}")

        // Initialize DetectionEngine singleton IMMEDIATELY
        // This prevents race conditions where services try to use it before creation
        try {
            val engine = DetectionEngineManager.getInstance(this)
            Log.i(TAG, "Ã¢Å“â€œ DetectionEngine singleton initialized successfully")
            Log.i(TAG, "  Instance hash: ${engine.hashCode()}")
        } catch (e: Exception) {
            Log.e(TAG, "Ã¢ÂÅ’ Failed to initialize DetectionEngine", e)
        }

        Log.i(TAG, "=== SafeScreen Application Started ===")
    }

    override fun onTerminate() {
        Log.i(TAG, "SafeScreen Application terminating")
        DetectionEngineManager.release()
        super.onTerminate()
    }
}