package com.safescreen.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.safescreen.guard.ml.DetectionEngineManager

class CaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private val TAG = "CaptureService"

    companion object {
        const val ACTION_START = "com.safescreen.guard.START_CAPTURE"
        const val ACTION_STOP = "com.safescreen.guard.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "safescreen_capture"

        var onOcrRegionsDetected: ((List<Rect>) -> Unit)? = null
        var onFrameCaptured: ((Bitmap) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CaptureService onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "=== CaptureService START ACTION ===")

                if (mediaProjection != null) {
                    Log.w(TAG, "Existing capture found – stopping before starting new one")
                    stopCapture()
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }

                val engine = DetectionEngineManager.getInstance(this)
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                try {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data!!)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { stopCapture() }
                    }, null)

                    DetectionEngineManager.setMediaProjection(mediaProjection)

                    // Connect callbacks with extensive logging
                    engine.onToxicRegionsDetected = { regions ->
                        if (regions.isNotEmpty()) {
                            Log.d(TAG, "🔍 OCR detected ${regions.size} toxic regions")
                            Log.d(TAG, "Regions: $regions")
                        }

                        // Verify callback exists
                        if (onOcrRegionsDetected != null) {
                            Log.d(TAG, "✅ Callback exists - invoking SafeScreenService")
                            onOcrRegionsDetected?.invoke(regions)
                        } else {
                            Log.e(TAG, "❌ onOcrRegionsDetected is NULL - SafeScreenService not connected!")
                            Log.e(TAG, "Make sure SafeScreenService is enabled in Accessibility Settings")
                        }
                    }

                    engine.onFrameCaptured = { bitmap ->
                        onFrameCaptured?.invoke(bitmap)
                    }

                    engine.startAudioMonitoringWithRetry()

                    Log.i(TAG, "✅ Services started: Screen Capture + Audio")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start services", e)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "CaptureService STOP ACTION")
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping capture...")
        mediaProjection?.stop()
        mediaProjection = null

        try {
            val engine = DetectionEngineManager.getInstance(this)
            //engine.stopAudioMonitoring()
            engine.onToxicRegionsDetected = null
            engine.onFrameCaptured = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine", e)
        }

        Log.i(TAG, "Capture fully stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Monitoring content" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("SafeScreen Guard")
            .setContentText("Monitoring screen and audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "CaptureService onDestroy()")
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}