package com.safescreen.guard.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * =============================================================================
 * DetectionEngine v5.2 — FULL NO-SUPPRESSION (CHILD-SAFE MODE)
 * =============================================================================
 *
 * CORE RULES:
 * -----------
 * ✔ Every RAW detection is considered valid
 * ✔ No IOU matching required for visibility
 * ✔ No STABLE_FRAMES threshold
 * ✔ No HIGH_CONF threshold
 * ✔ No TTL aging
 * ✔ No grid suppression / no small box removal
 * ✔ Tracking is preserved *only* for merging (not filtering)
 * ✔ Hysteresis ensures mask stays visible between frames
 *
 * RESULT:
 * -------
 * ✨ Mask NEVER flickers
 * ✨ Mask NEVER disappears on stable toxic content
 * ✨ ALL toxic regions ALWAYS get masked
 * ✨ Output is stable across all frames
 */
class DetectionEngine(private val context: Context) {

    private val TAG = "DetectionEngine"

    private data class TrackedBox(
        var rect: Rect,
        var lastSeen: Long
    )

    private val tracked = mutableListOf<TrackedBox>()
    private val seq = AtomicLong(0)
    private val mutex = Mutex()
    private var detector: ToxicDetector? = null
    private val initDone = AtomicBoolean(false)

    // =====================================================================
    // OUTPUT HYSTERESIS
    // =====================================================================
    private var lastOutput: List<RectF> = emptyList()
    private var lastOutputTime = 0L
    private val OUTPUT_HOLD_MS = 600L

    // track window change
    @Volatile private var windowKey: String = ""

    private var textDetector: ToxicTextDetector? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            try {
                detector = ToxicDetector(context)
                textDetector = ToxicTextDetector(context)
                initDone.set(true)
                Log.i(TAG, "✓ DetectionEngine initialized (v5.2 NO-SUPPRESSION)")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed initializing DetectionEngine", e)
            }
        }
    }

    // =====================================================================
    // MAIN ENTRY
    // =====================================================================
    suspend fun analyzeScreenSync(
        bitmap: Bitmap,
        winKey: String
    ): Pair<Long, List<RectF>> = mutex.withLock {

        val frameId = seq.incrementAndGet()

        if (!initDone.get() || bitmap.isRecycled) {
            return frameId to lastOutput
        }

        val now = SystemClock.uptimeMillis()

        // STEP 1 — RAW detections (no suppression)
        val raw = detector
            ?.analyzeWithConfidence(bitmap, DetectionConfig.CONFIDENCE_THRESHOLD)
            .orEmpty()

        if (raw.isNotEmpty()) {
            Log.d(TAG, "📦 RAW detections = ${raw.size}")
        }

        // STEP 2 — Replace tracking completely (we keep it only for merge)
        tracked.clear()
        for ((rect, _) in raw) {
            tracked.add(TrackedBox(rect, now))
        }

        // STEP 3 — Convert to simple list
        val rects = tracked.map { it.rect }

        // STEP 4 — Merge adjacent boxes vertically
        val merged = mergeRectGroups(rects, bitmap.width, bitmap.height)

//      STEP 5 — Apply hysteresis (no suppression)

        val imgRegions = applyOutputHysteresis(merged, bitmap.width, bitmap.height)
        val textRegions = textDetector?.detectToxicText(bitmap).orEmpty()

//      If ANY toxic region exists → FULLSCREEN MASK
        val final = if (imgRegions.isNotEmpty() || textRegions.isNotEmpty()) {
            listOf(RectF(0f, 0f, 1f, 1f))
        } else {
            emptyList()
        }

        lastOutput = final
        lastOutputTime = now

        return frameId to final

    }

    // =====================================================================
    // MERGE (unchanged — safe)
    // =====================================================================
    private fun mergeRectGroups(
        rects: List<Rect>,
        w: Int,
        h: Int
    ): List<Rect> {

        if (rects.isEmpty()) return emptyList()
        if (rects.size == 1) return rects

        val sorted = rects.sortedBy { it.top }
        val groups = mutableListOf<MutableList<Rect>>()
        var current = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val prev = current.last()
            val next = sorted[i]

            val gap = next.top - prev.bottom
            val gapThreshold = (h * 0.08f).toInt()

            if (gap <= gapThreshold) {
                current.add(next)
            } else {
                groups.add(current)
                current = mutableListOf(next)
            }
        }
        groups.add(current)

        val merged = mutableListOf<Rect>()

        for (g in groups) {
            val left = g.minOf { it.left }
            val top = g.minOf { it.top }
            val right = g.maxOf { it.right }
            val bottom = g.maxOf { it.bottom }
            merged.add(Rect(left, top, right, bottom))
        }

        return merged
    }

    // =====================================================================
    // HYSTERESIS (still required)
    // =====================================================================
    private fun applyOutputHysteresis(
        rects: List<Rect>,
        w: Int,
        h: Int
    ): List<RectF> {

        val now = SystemClock.uptimeMillis()

        if (rects.isNotEmpty()) {
            lastOutputTime = now
            return normalize(rects, w, h)
        }

        if (now - lastOutputTime <= OUTPUT_HOLD_MS && lastOutput.isNotEmpty()) {
            return lastOutput
        }

        return emptyList()
    }

    private fun normalize(
        rects: List<Rect>,
        w: Int,
        h: Int
    ): List<RectF> {
        return rects.map { r ->
            RectF(
                r.left.toFloat() / w,
                r.top.toFloat() / h,
                r.right.toFloat() / w,
                r.bottom.toFloat() / h
            )
        }
    }

    // =====================================================================
    // WINDOW CHANGE RESET
    // =====================================================================
    suspend fun clearForNewContext(newKey: String): List<RectF> =
        mutex.withLock {
            if (windowKey != newKey) {
                Log.d(TAG, "🪟 Foreground changed: $windowKey → $newKey")
                windowKey = newKey

                tracked.clear()   // only removes tracking, NOT output
            }
            return lastOutput     // maintain output until hysteresis expires
        }

    // =====================================================================
    // CLEANUP
    // =====================================================================
    fun close() {
        scope.cancel()
        detector?.close()
    }
}
