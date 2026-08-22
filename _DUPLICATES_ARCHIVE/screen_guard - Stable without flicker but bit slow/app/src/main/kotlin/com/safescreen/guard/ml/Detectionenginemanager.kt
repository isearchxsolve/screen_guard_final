package com.safescreen.guard.ml

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log

/**
 * Singleton manager - one DetectionEngine instance shared across the whole app
 */
object DetectionEngineManager {
    private const val TAG = "DetectionEngineManager"

    @Volatile
    private var instance: DetectionEngine? = null
    private val lock = Any()

    fun getInstance(context: Context): DetectionEngine {
        var local = instance
        if (local == null) {
            synchronized(lock) {
                local = instance
                if (local == null) {
                    local = DetectionEngine(context.applicationContext)
                    instance = local
                    Log.i(TAG, "Created NEW DetectionEngine instance (hash=${local.hashCode()})")
                }
            }
        }
        return local!!
    }

    fun setMediaProjection(projection: MediaProjection?) {
        Log.d(TAG, "setMediaProjection called - projection ${if (projection == null) "NULL" else "OK"}")
        synchronized(lock) {
            instance?.setMediaProjection(projection)
                ?: Log.e(TAG, "setMediaProjection called but instance is NULL! Call getInstance() first.")
        }
    }

    // NEW: Control Pause/Resume of the engine
    fun setDetectionPaused(paused: Boolean) {
        synchronized(lock) {
            instance?.setDetectionPaused(paused)
        }
    }

    // OPTIONAL: simple readiness check (you can remove if you never use it)
    fun isRunning(): Boolean = synchronized(lock) { instance != null }

    fun release() {
        synchronized(lock) {
            Log.i(TAG, "Releasing DetectionEngine instance")
            instance?.close()
            instance = null
        }
    }
}
