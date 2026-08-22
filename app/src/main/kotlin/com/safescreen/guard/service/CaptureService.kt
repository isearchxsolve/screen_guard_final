package com.safescreen.guard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.WindowMetrics
import android.graphics.PixelFormat

/**
 * FIXED CaptureService
 * - Uses safe WindowMetrics API (Android 30+)
 * - Stable VirtualDisplay + ImageReader creation
 * - Correct lifecycle cleanup
 * - Guaranteed handoff to SafeScreenService
 */
class CaptureService : Service() {

    private val TAG = "CaptureService"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        const val ACTION_START = "START"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val CHANNEL_ID = "safescreen"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

            if (data == null) {
                Log.e(TAG, "Projection data missing!")
                stopSelf()
                return START_NOT_STICKY
            }

            startForeground(NOTIFICATION_ID, createNotification())
            startProjection(resultCode, data)
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped by system")
                    stopSelf()
                }
            }, null)

            // ---- FIX: Correct metrics (Android 13+) ----
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val width: Int
            val height: Int
            val density: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics: WindowMetrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                width = bounds.width()
                height = bounds.height()
                density = resources.displayMetrics.densityDpi
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                width = dm.widthPixels
                height = dm.heightPixels
                density = dm.densityDpi
            }

            Log.i(TAG, "Capture resolution = ${width}x$height @ $density dpi")

            // ---- ImageReader ----
            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                4
            )

            // ---- Virtual Display ----
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SafeScreenVirtualDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            Log.i(TAG, "VirtualDisplay created successfully")

            // ---- Pass to SafeScreenService ----
            val svc = SafeScreenService.getInstance()
            if (svc != null) {
                svc.setMediaProjection(mediaProjection, imageReader)
                Log.i(TAG, "MediaProjection passed to SafeScreenService")
            } else {
                Log.w(TAG, "SafeScreenService not ready yet (will retry on next start)")
            }

            Log.i(TAG, "MediaProjection started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SafeScreen Protection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SafeScreen Active")
                .setContentText("Screen & Audio protection running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("SafeScreen Active")
                .setContentText("Screen & Audio protection running")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "CaptureService stopping")

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
