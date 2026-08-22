package com.safescreen.guard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.safescreen.guard.ml.DetectionEngineManager
import com.safescreen.guard.ui.ToxicMaskView

class SafeScreenService : AccessibilityService() {

    private val TAG = "SafeScreenService"
    private lateinit var windowManager: WindowManager
    private lateinit var toxicMaskView: ToxicMaskView
    private var isOverlayAttached = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=== SafeScreenService STARTING ===")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        toxicMaskView = ToxicMaskView(this)
        setupOverlay()

        val engine = DetectionEngineManager.getInstance(this)
        engine.onToxicRegionsDetected = { toxicRects ->
            Log.d(TAG, "🔴 Detected ${toxicRects.size} toxic regions")
            mainHandler.post {
                toxicMaskView.updateRegions(toxicRects)
            }
        }

        Log.i(TAG, "SafeScreenService connected - Detection callback registered")
    }

    private fun setupOverlay() {
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT

            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }

            // CRITICAL FIX: Use TRANSLUCENT format (Android requirement for overlays)
            format = PixelFormat.TRANSLUCENT

            // CRITICAL FIX: Accept system alpha adjustment, remove FLAG_NOT_TOUCHABLE conflict
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            // CRITICAL: Set alpha to 0.80 to match system requirement
            // System warning: "alpha = 1.00 > 0.80, setting alpha to 0.80"
            alpha = 0.80f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // CRITICAL: Use HARDWARE layer for smooth rendering
        toxicMaskView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        toxicMaskView.setBackgroundColor(Color.TRANSPARENT)

        if (!isOverlayAttached) {
            try {
                windowManager.addView(toxicMaskView, params)
                isOverlayAttached = true
                Log.i(TAG, "✅ Overlay attached (alpha=0.80)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to add overlay", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Minimal logging
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        if (isOverlayAttached) {
            try {
                windowManager.removeView(toxicMaskView)
                isOverlayAttached = false
                Log.i(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        super.onDestroy()
    }
}