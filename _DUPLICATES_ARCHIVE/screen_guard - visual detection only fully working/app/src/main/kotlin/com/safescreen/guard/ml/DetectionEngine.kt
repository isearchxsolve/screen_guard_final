package com.safescreen.guard.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

class DetectionEngine(private val context: Context) : Closeable {
    private val TAG = "DetectionEngine"

    private var toxicDetector: ToxicDetector? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var mainHandler: Handler? = null

    private var rawBitmap: Bitmap? = null
    private var frameCount = 0

    // Throttle to ~15 FPS
    private val THROTTLE_MS = 66L
    private var lastAnalysisTime = 0L

    // === ANTI-FLICKER SETTINGS ===

    // Require 3 consecutive frames to START showing (prevents flashes)
    private val MIN_HITS_START = 3

    // Keep mask for 15 frames (~1 second) after loss (prevents flicker)
    private val MAX_MISS_KEEP = 15

    // Reduced smoothing for faster response (0.3 = 30% new, 70% old)
    private val SMOOTHING_FACTOR = 0.3f

    // Tracking overlap threshold
    private val MIN_TRACKING_OVERLAP = 0.15f

    // === Detection Change Detection ===
    private var lastDetectionCount = 0
    private val DETECTION_COUNT_THRESHOLD = 2  // Ignore changes of 1-2 boxes
    private val POSITION_SHIFT_THRESHOLD = 50  // Pixels

    private val trackedRegions = mutableListOf<TrackedRegion>()
    private var nextRegionId = 0
    private var captureWidth = 0
    private var captureHeight = 0

    private val lastNotifiedRects = mutableListOf<Rect>()

    var onToxicRegionsDetected: ((List<Rect>) -> Unit)? = null

    init {
        startThread()
        mainHandler = Handler(Looper.getMainLooper())
        toxicDetector = ToxicDetector(context)
        Log.i(TAG, "DetectionEngine initialized")
    }

    private fun startThread() {
        handlerThread = HandlerThread("CaptureThread").apply {
            start()
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        }
        handler = Handler(handlerThread!!.looper)
        Log.i(TAG, "Capture thread started (alive=${handlerThread!!.isAlive})")
    }

    fun setMediaProjection(projection: MediaProjection?) {
        Log.i(TAG, "=== setMediaProjection called ===")
        releaseResources()
        if (projection == null) {
            Log.w(TAG, "MediaProjection is NULL")
            return
        }
        mediaProjection = projection
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "⚠️ MediaProjection stopped by system")
                releaseResources()
            }
        }, handler)

        Log.i(TAG, "MediaProjection registered (hash=${mediaProjection.hashCode()})")
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            captureWidth = metrics.bounds.width()
            captureHeight = metrics.bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            captureWidth = point.x
            captureHeight = point.y
        }

        Log.i(TAG, "📺 Virtual display: ${captureWidth}x${captureHeight}")

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)

        // CRITICAL: Set listener BEFORE creating VirtualDisplay
        imageReader?.setOnImageAvailableListener({ reader ->
            frameCount++
            Log.v(TAG, "📷 Frame $frameCount received")

            val image = reader.acquireLatestImage()
            if (image == null) {
                Log.w(TAG, "⚠️ Frame $frameCount: image is NULL")
                return@setOnImageAvailableListener
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisTime < THROTTLE_MS) {
                image.close()
                Log.v(TAG, "⏭️ Frame $frameCount: throttled")
                return@setOnImageAvailableListener
            }
            lastAnalysisTime = now

            val cleanBitmap = removePadding(image)
            image.close()

            if (cleanBitmap != null) {
                Log.d(TAG, "🔍 Frame $frameCount: analyzing ${cleanBitmap.width}x${cleanBitmap.height}")
                toxicDetector?.analyze(cleanBitmap) { rawRects ->
                    processDetections(rawRects)
                }
            } else {
                Log.w(TAG, "❌ Frame $frameCount: bitmap is NULL")
            }
        }, handler)

        Log.i(TAG, "ImageReader created:")
        Log.i(TAG, "  - Surface valid: ${imageReader?.surface?.isValid}")
        Log.i(TAG, "  - Handler: ${handler?.looper?.thread?.name}")

        val density = context.resources.displayMetrics.densityDpi

        // TRY PRIMARY FLAG: AUTO_MIRROR (most compatible)
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SafeScreen", captureWidth, captureHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ AUTO_MIRROR failed, trying PUBLIC flag", e)
            // FALLBACK: Use PUBLIC flag (works on some devices where AUTO_MIRROR fails)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SafeScreen", captureWidth, captureHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader?.surface, null, handler
            )
        }

        if (virtualDisplay == null) {
            Log.e(TAG, "❌ VirtualDisplay creation FAILED!")
            return
        }

        Log.i(TAG, "✅ VirtualDisplay created successfully")
        Log.i(TAG, "   - Display ID: ${virtualDisplay?.display?.displayId}")
        Log.i(TAG, "   - Display name: ${virtualDisplay?.display?.name}")
        Log.i(TAG, "   - Surface: ${imageReader?.surface}")
        Log.i(TAG, "   - Handler thread alive: ${handlerThread?.isAlive}")
        Log.i(TAG, "   ⏳ Waiting for frames...")

        // Force a small delay to ensure display is ready
        handler?.postDelayed({
            Log.i(TAG, "   📊 Status check: $frameCount frames received so far")
            if (frameCount == 0) {
                Log.e(TAG, "   ⚠️ NO FRAMES RECEIVED! VirtualDisplay may not be rendering")
            }
        }, 2000)
    }

    private fun removePadding(image: Image): Bitmap? {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bufferWidth = image.width + rowPadding / pixelStride

            if (rawBitmap == null || rawBitmap!!.width != bufferWidth || rawBitmap!!.height != image.height) {
                rawBitmap?.recycle()
                rawBitmap = Bitmap.createBitmap(bufferWidth, image.height, Bitmap.Config.ARGB_8888)
            }

            buffer.rewind()
            rawBitmap!!.copyPixelsFromBuffer(buffer)
            return Bitmap.createBitmap(rawBitmap!!, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing padding", e)
            return null
        }
    }

    private fun processDetections(rawRects: List<Rect>) {
        val now = SystemClock.elapsedRealtime()
        val activeRegions = mutableListOf<Rect>()
        val matched = BooleanArray(rawRects.size)
        val nextRegions = mutableListOf<TrackedRegion>()

        // 1. Update existing trackers
        for (tracked in trackedRegions) {
            var bestIdx = -1
            var bestScore = 0f

            for (i in rawRects.indices) {
                if (matched[i]) continue
                val score = calculateMatchScore(tracked.rect, rawRects[i])

                if (score > bestScore && score > MIN_TRACKING_OVERLAP) {
                    bestScore = score
                    bestIdx = i
                }
            }

            if (bestIdx != -1) {
                // MATCH FOUND: Update position
                matched[bestIdx] = true
                val newRect = rawRects[bestIdx]

                // SMOOTHING: Reduced factor for faster response
                val smoothedLeft = lerp(tracked.rect.left, newRect.left, SMOOTHING_FACTOR)
                val smoothedTop = lerp(tracked.rect.top, newRect.top, SMOOTHING_FACTOR)
                val smoothedRight = lerp(tracked.rect.right, newRect.right, SMOOTHING_FACTOR)
                val smoothedBottom = lerp(tracked.rect.bottom, newRect.bottom, SMOOTHING_FACTOR)

                val finalRect = Rect(smoothedLeft, smoothedTop, smoothedRight, smoothedBottom)

                val hits = tracked.consecutiveHits + 1
                val isStable = hits >= MIN_HITS_START

                nextRegions.add(tracked.copy(
                    rect = finalRect,
                    lastSeen = now,
                    consecutiveHits = hits,
                    missedCount = 0,
                    isStable = isStable
                ))

                if (isStable) activeRegions.add(finalRect)

            } else {
                // TRACKING LOST: Extended hysteresis
                if (tracked.isStable && tracked.missedCount < MAX_MISS_KEEP) {
                    val updatedTracked = tracked.copy(missedCount = tracked.missedCount + 1)
                    nextRegions.add(updatedTracked)
                    // Keep showing the old position
                    activeRegions.add(tracked.rect)
                }
            }
        }

        // 2. Add new detections
        for (i in rawRects.indices) {
            if (!matched[i]) {
                val newRegion = TrackedRegion(
                    nextRegionId++,
                    rawRects[i],
                    now,
                    now,
                    1,
                    0,
                    false  // Never stable on first frame
                )
                nextRegions.add(newRegion)
            }
        }

        trackedRegions.clear()
        trackedRegions.addAll(nextRegions)

        // Smart UI update
        notifyUIIfSignificantChange(activeRegions)
    }

    private fun notifyUIIfSignificantChange(activeRegions: List<Rect>) {
        // Check 1: Detection count changed significantly?
        val countDiff = kotlin.math.abs(activeRegions.size - lastDetectionCount)
        val significantCountChange = countDiff > DETECTION_COUNT_THRESHOLD

        // Check 2: Positions shifted significantly?
        var significantPositionChange = false
        if (activeRegions.size == lastNotifiedRects.size) {
            for (i in activeRegions.indices) {
                if (i >= lastNotifiedRects.size) break
                val oldRect = lastNotifiedRects[i]
                val newRect = activeRegions[i]

                val dx = kotlin.math.abs(newRect.centerX() - oldRect.centerX())
                val dy = kotlin.math.abs(newRect.centerY() - oldRect.centerY())

                if (dx > POSITION_SHIFT_THRESHOLD || dy > POSITION_SHIFT_THRESHOLD) {
                    significantPositionChange = true
                    break
                }
            }
        } else {
            significantPositionChange = true
        }

        // Update UI only if change is significant
        if (significantCountChange || significantPositionChange) {
            lastDetectionCount = activeRegions.size
            updateUI(activeRegions)
        }
    }

    private fun lerp(start: Int, end: Int, factor: Float): Int {
        return (start + (end - start) * factor).toInt()
    }

    private fun updateUI(activeRegions: List<Rect>) {
        lastNotifiedRects.clear()
        lastNotifiedRects.addAll(activeRegions)

        mainHandler?.post {
            onToxicRegionsDetected?.invoke(activeRegions.toList())
        }
    }

    private fun calculateMatchScore(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (left >= right || top >= bottom) return 0f
        val intersection = (right - left) * (bottom - top).toFloat()
        val union = (a.width() * a.height()) + (b.width() * b.height()) - intersection
        return if (union > 0) intersection / union else 0f
    }

    private fun releaseResources() {
        Log.i(TAG, "Releasing resources")
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection = null
        rawBitmap?.recycle()
    }

    override fun close() {
        Log.i(TAG, "DetectionEngine closing")
        releaseResources()
        toxicDetector?.close()
        handlerThread?.quitSafely()
        mainHandler?.removeCallbacksAndMessages(null)
    }
}

data class TrackedRegion(
    val id: Int,
    val rect: Rect,
    val firstSeen: Long,
    val lastSeen: Long,
    val consecutiveHits: Int,
    val missedCount: Int,
    val isStable: Boolean
)