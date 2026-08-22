package com.safescreen.guard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.safescreen.guard.ml.DetectionEngineManager
import com.safescreen.guard.ui.ToxicMaskView

class SafeScreenService : AccessibilityService() {
    private val TAG = "SafeScreenService"
    private var windowManager: WindowManager? = null
    private var maskView: ToxicMaskView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasStartedDetection = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=== SafeScreenService CONNECTED ===")

        setupOverlay()

        CaptureService.onOcrRegionsDetected = { newRegions ->
            mainHandler.post {
                maskView?.updateRegions(newRegions)
            }
        }

        mainHandler.postDelayed({
            if (!hasStartedDetection) {
                DetectionEngineManager.setDetectionPaused(false)
                hasStartedDetection = true
            }
        }, 1000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        maskView = ToxicMaskView(this)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        // REMOVED FLAG_SECURE: The engine MUST see the screen.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(maskView, params)
            Log.i(TAG, "✅ Overlay added (Stable Mode)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ FAILED to add overlay", e)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try { windowManager?.removeView(maskView) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onInterrupt() {}
}