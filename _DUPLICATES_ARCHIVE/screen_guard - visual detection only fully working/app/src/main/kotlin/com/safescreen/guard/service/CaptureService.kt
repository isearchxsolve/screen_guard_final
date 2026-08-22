package com.safescreen.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "CaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                Log.i(TAG, "=== START_CAPTURE command received ===")
                Log.i(TAG, "  Result code: $resultCode")
                Log.i(TAG, "  Data intent: ${data != null}")

                // Start as foreground service FIRST
                startForeground(NOTIFICATION_ID, createNotification())
                Log.i(TAG, "✅ Running as foreground service")

                // Ensure DetectionEngine singleton exists FIRST
                Log.d(TAG, "Ensuring DetectionEngine singleton exists...")
                val engine = DetectionEngineManager.getInstance(this)
                Log.d(TAG, "  Engine instance: ${engine.hashCode()}")

                // Now initialize MediaProjection
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                try {
                    mediaProjection = projectionManager.getMediaProjection(resultCode, data!!)

                    if (mediaProjection == null) {
                        Log.e(TAG, "❌ MediaProjection is NULL! Result code: $resultCode")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    // Register callback to detect if projection stops
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "⚠️ MediaProjection stopped by system")
                        }
                    }, null)

                    // Log MediaProjection state
                    Log.i(TAG, "✅ MediaProjection created successfully")
                    Log.d(TAG, "  - Object hash: ${mediaProjection.hashCode()}")
                    Log.d(TAG, "  - Result code: $resultCode")
                    Log.d(TAG, "  - Data extras: ${data.extras?.keySet()?.joinToString()}")

                    // Pass MediaProjection to DetectionEngine
                    DetectionEngineManager.setMediaProjection(mediaProjection)

                    Log.i(TAG, "✅ MediaProjection passed to DetectionEngine")
                    Log.i(TAG, "=== Capture initialization complete ===")

                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ SecurityException - User denied screen capture", e)
                    stopSelf()
                    return START_NOT_STICKY
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to initialize MediaProjection", e)
                    e.printStackTrace()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "STOP_CAPTURE command received")
                stopCapture()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SafeScreen screen capture service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeScreen Guard")
            .setContentText("Monitoring screen content")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping capture...")
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "Capture stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        Log.i(TAG, "CaptureService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}