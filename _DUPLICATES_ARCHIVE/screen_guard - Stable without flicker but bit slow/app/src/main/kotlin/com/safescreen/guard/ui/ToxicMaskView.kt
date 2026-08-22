package com.safescreen.guard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

class ToxicMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "ToxicMaskView"

    private val displayedRects = ArrayList<Rect>()
    private val drawLock = Any()

    // Debouncing
    private var pendingUpdate: List<Rect>? = null
    private var isUpdateScheduled = false
    private val DEBOUNCE_DELAY_MS = 150L

    private val updateRunnable = Runnable {
        pendingUpdate?.let { rects ->
            applyMaskUpdate(rects)
        }
        pendingUpdate = null
        isUpdateScheduled = false
    }

    private var maskAlpha = 200  // Increased for better visibility

    private val fadeAnimator = ValueAnimator.ofInt(200, 0).apply {
        duration = 300
        interpolator = DecelerateInterpolator()
        addUpdateListener { animation ->
            maskAlpha = animation.animatedValue as Int
            maskPaint.alpha = maskAlpha
            invalidate()
        }
    }

    private val maskPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 200
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        Log.i(TAG, "ToxicMaskView initialized - Optimized Mode")
    }

    fun updateRegions(newRects: List<Rect>) {
        val startTime = System.nanoTime()

        synchronized(drawLock) {
            // OPTIMIZATION 1: Check if masks are identical (exact match after snapping)
            if (areMasksIdentical(newRects, displayedRects)) {
                // No changes, no redraw needed
                return
            }

            // OPTIMIZATION 2: Debounce rapid updates
            pendingUpdate = newRects

            if (!isUpdateScheduled) {
                isUpdateScheduled = true
                postDelayed(updateRunnable, DEBOUNCE_DELAY_MS)
            }
        }

        val durationMs = (System.nanoTime() - startTime) / 1_000_000f
        if (durationMs > 2.0f) {
            Log.w(TAG, "⚠️ Slow mask check: ${durationMs}ms")
        }
    }

    private fun applyMaskUpdate(newRects: List<Rect>) {
        val startTime = System.nanoTime()

        synchronized(drawLock) {
            // Calculate diff for logging
            val added = newRects.filter { newRect ->
                displayedRects.none { it == newRect }
            }

            val removed = displayedRects.filter { oldRect ->
                newRects.none { it == oldRect }
            }

            // OPTIMIZATION 3: Skip redraw if nothing actually changed
            if (added.isEmpty() && removed.isEmpty()) {
                Log.d(TAG, "✓ Masks unchanged - no redraw")
                return
            }

            Log.d(TAG, "🔄 Mask diff: +${added.size} -${removed.size}")

            displayedRects.clear()
            displayedRects.addAll(newRects)

            // OPTIMIZATION 4: Use partial invalidate for small changes
            if (added.size + removed.size < 3) {
                // Only invalidate changed regions
                for (rect in added + removed) {
                    // Add padding to ensure borders are redrawn
                    invalidate(
                        rect.left - 10,
                        rect.top - 10,
                        rect.right + 10,
                        rect.bottom + 10
                    )
                }
            } else {
                // Full redraw for major changes
                invalidate()
            }

            // Reset opacity for active regions
            if (displayedRects.isNotEmpty()) {
                restoreMaskOpacity()
            }
        }

        val durationMs = (System.nanoTime() - startTime) / 1_000_000f
        Log.d(TAG, "✓ Mask update completed in ${durationMs}ms")
    }

    /**
     * Check if two rect lists are identical (after snapping, they should match exactly)
     */
    /**
     * Checks if two lists of masks are essentially identical,
     * allowing small coordinate differences due to MLKit jitter
     */
    private fun areMasksIdentical(a: List<Rect>, b: List<Rect>): Boolean {
        if (a.size != b.size) return false

        // Sort by position for reliable comparison
        val sortedA = a.sortedWith(compareBy<Rect> { it.left }.thenBy { it.top })
        val sortedB = b.sortedWith(compareBy<Rect> { it.left }.thenBy { it.top })

        val tolerance = 20  // pixels — covers typical MLKit bounding box variation

        return sortedA.zip(sortedB).all { (rectA, rectB) ->
            abs(rectA.left - rectB.left) <= tolerance &&
                    abs(rectA.top - rectB.top) <= tolerance &&
                    abs(rectA.right - rectB.right) <= tolerance &&
                    abs(rectA.bottom - rectB.bottom) <= tolerance
        }
    }

    fun fadeOutMasks() {
        if (!fadeAnimator.isRunning && maskAlpha > 0) {
            fadeAnimator.start()
        }
    }

    private fun restoreMaskOpacity() {
        if (fadeAnimator.isRunning) fadeAnimator.cancel()
        maskAlpha = 200
        maskPaint.alpha = 200
    }

    fun clearMasks() {
        synchronized(drawLock) {
            if (displayedRects.isNotEmpty() || pendingUpdate != null) {
                // Cancel pending updates
                removeCallbacks(updateRunnable)
                isUpdateScheduled = false
                pendingUpdate = null

                displayedRects.clear()
                restoreMaskOpacity()
                invalidate()
                Log.d(TAG, "🗑️ Masks cleared")
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        synchronized(drawLock) {
            if (displayedRects.isEmpty()) return

            // Draw all masks
            for (rect in displayedRects) {
                canvas.drawRect(rect, maskPaint)
                canvas.drawRect(rect, borderPaint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        fadeAnimator.cancel()
        removeCallbacks(updateRunnable)
        super.onDetachedFromWindow()
    }
}