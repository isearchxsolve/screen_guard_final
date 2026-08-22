package com.safescreen.guard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View

class ToxicMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "ToxicMaskView"
    private val displayedRects = mutableListOf<Rect>()
    private var isTestMode = false

    // SOLID BLACK mask (80% opacity)
    private val maskPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 204  // 80% opacity
        isAntiAlias = false
    }

    private val testPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 204
        isAntiAlias = false
    }

    private val borderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = false
    }

    fun setTestMode(enabled: Boolean) {
        if (isTestMode != enabled) {
            isTestMode = enabled
            invalidate()
        }
    }

    fun updateRegions(rects: List<Rect>) {
        // Optimization: Check if update is needed
        if (rects.size == displayedRects.size) {
            var same = true
            for (i in rects.indices) {
                // Check if rectangles are "close enough" (within 5px)
                val r1 = rects[i]
                val r2 = displayedRects[i]
                val dx = kotlin.math.abs(r1.centerX() - r2.centerX())
                val dy = kotlin.math.abs(r1.centerY() - r2.centerY())
                val dw = kotlin.math.abs(r1.width() - r2.width())
                val dh = kotlin.math.abs(r1.height() - r2.height())

                if (dx > 5 || dy > 5 || dw > 5 || dh > 5) {
                    same = false
                    break
                }
            }
            if (same) {
                // Visually identical, skip redraw
                return
            }
        }

        Log.d(TAG, "📍 Updating mask: ${rects.size} regions")

        displayedRects.clear()
        displayedRects.addAll(rects)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Clear canvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (displayedRects.isEmpty()) {
            return
        }

        val activePaint = if (isTestMode) testPaint else maskPaint

        // Draw all masks
        for (rect in displayedRects) {
            canvas.drawRect(rect, activePaint)

            if (isTestMode) {
                canvas.drawRect(rect, borderPaint)
            }
        }

        Log.d(TAG, "✏️ Drew ${displayedRects.size} masks")
    }
}