package com.safescreen.guard.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * =============================================================
 * ToxicMaskView v4.1 — Fullscreen Mask (NO STUCK / NO DISAPPEAR BUG)
 * =============================================================
 *
 * Fixes Added:
 * ------------
 * ✔ postInvalidateOnAnimation() → forces redraw reliably
 * ✔ setWillNotDraw(false) → prevents hardware-layer drop
 * ✔ Fullscreen mask appears EVERY TIME a detection arrives
 * ✔ Mask clears instantly in safe areas
 * ✔ No more "mask disappears forever"
 */
class ToxicMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "ToxicMaskView"

    private val normalizedMasks = mutableListOf<RectF>()

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 235
        style = Paint.Style.FILL
        isDither = true
        isFilterBitmap = true
    }

    @Volatile
    private var showFullMask = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setWillNotDraw(false)          // <---- IMPORTANT FIX
    }

    fun acceptDetections(rects: List<RectF>) {
        synchronized(normalizedMasks) {
            normalizedMasks.clear()
            rects.forEach { r ->
                if (r.width() > 0.0001f && r.height() > 0.0001f) {
                    normalizedMasks.add(RectF(r))
                }
            }

            showFullMask = normalizedMasks.isNotEmpty()
        }

        postInvalidateOnAnimation()     // <---- IMPORTANT FIX

        Log.d(TAG, if (showFullMask)
            "🎭 FULLSCREEN MASK (detections=${normalizedMasks.size})"
        else
            "🧽 No detections → no mask"
        )
    }

    fun clearMasks() {
        synchronized(normalizedMasks) {
            if (normalizedMasks.isNotEmpty() || showFullMask) {
                normalizedMasks.clear()
                showFullMask = false
                Log.d(TAG, "🧽 MaskView → Cleared")
                postInvalidateOnAnimation()  // <---- REQUIRED FIX
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showFullMask) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        if (vw <= 0f || vh <= 0f) return

        canvas.drawRect(0f, 0f, vw, vh, maskPaint)
    }
}
