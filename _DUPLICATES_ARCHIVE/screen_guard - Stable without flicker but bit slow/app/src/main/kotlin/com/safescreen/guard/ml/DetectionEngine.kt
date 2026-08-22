package com.safescreen.guard.ml

import android.graphics.Color
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class DetectionEngine(private val context: Context) : Closeable {
    private val TAG = "DetectionEngine"

    // --- CONFIGURATION ---
    private val CAPTURE_THROTTLE_MS = 200L
    private val MEMORY_DURATION_MS = 3000L

    // --- TRACKING STATE ---
    private data class TrackedMask(
        var rect: Rect,
        var lastSeenTime: Long
    )
    private val trackedMasks = java.util.Collections.synchronizedList(ArrayList<TrackedMask>())

    // --- RESOURCES ---
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var toxicDetector: ToxicDetector? = null
    private val mlExecutor = Executors.newSingleThreadExecutor()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0

    // --- SYSTEM RESOURCES ---
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var mainHandler: Handler? = null

    // --- STATE ---
    private var lastAnalysisTime = 0L
    private val isBusy = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val currentOcrTask = AtomicInteger(0)

    // --- ENHANCED CHANGE DETECTION ---
    private val SIGNATURE_SIZE = 32
    private val signatureBitmap = Bitmap.createBitmap(SIGNATURE_SIZE, SIGNATURE_SIZE, Bitmap.Config.ARGB_8888)
    private val signatureCanvas = Canvas(signatureBitmap)
    private val signaturePaint = Paint().apply { isFilterBitmap = true }
    private val signatureRectDest = Rect(0, 0, SIGNATURE_SIZE, SIGNATURE_SIZE)
    private val currentSignaturePixels = IntArray(SIGNATURE_SIZE * SIGNATURE_SIZE)
    private var previousSignaturePixels: IntArray? = null

    // TUNED SENSITIVITY (More conservative to avoid false positives)
    private val PIXEL_DIFF_THRESHOLD = 35           // Increased - less sensitive to minor changes
    private val UNMASKED_CHANGE_THRESHOLD = 8       // Increased - requires more real content change
    private val GLOBAL_SCROLL_THRESHOLD = 150       // Increased - requires bigger scroll to trigger
    private val MASK_EXPANSION_PIXELS = 30          // Expand mask areas to avoid edge detection

    // NEW: Stability tracking to prevent drift
    private var consecutiveNoChangeFrames = 0
    private val STABILITY_RESET_THRESHOLD = 5       // Reset reference after N stable frames
    private var framesSinceLastScan = 0
    private val MAX_FRAMES_WITHOUT_SCAN = 50        // Force scan every 50 frames to prevent total freeze

    // --- CALLBACKS ---
    var onToxicRegionsDetected: ((List<Rect>) -> Unit)? = null
    var onFrameCaptured: ((Bitmap) -> Unit)? = null

    private val fastToxicKeywords = listOf("kill yourself", "kys", "suicide", "shit", "bitch", "whore", "fuck")

    init {
        startCaptureThread()
        mainHandler = Handler(Looper.getMainLooper())

        mlExecutor.execute {
            try { toxicDetector = ToxicDetector(context) } catch (e: Exception) { }
        }
        Log.i(TAG, "DetectionEngine: Smart Drift Correction v2 Active")
    }

    private fun startCaptureThread() {
        handlerThread = HandlerThread("CaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
    }

    fun setMediaProjection(projection: MediaProjection?) {
        releaseDisplayResources()
        if (projection == null) return
        isClosed.set(false)
        mediaProjection = projection
        setupVirtualDisplay()
    }

    private fun handleImageAvailable(reader: ImageReader) {
        if (isClosed.get() || isPaused.get()) {
            reader.acquireLatestImage()?.close()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTime < CAPTURE_THROTTLE_MS || isBusy.get()) {
            reader.acquireLatestImage()?.close()
            return
        }
        lastAnalysisTime = now

        val image = reader.acquireLatestImage() ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val width = image.width + rowPadding / pixelStride

            val currentBitmap = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            currentBitmap.copyPixelsFromBuffer(buffer)

            onFrameCaptured?.invoke(currentBitmap)

            // ENHANCED CHANGE CHECK
            val changeResult = hasScreenChanged(currentBitmap)

            when {
                changeResult == ChangeType.NO_CHANGE -> {
                    // Screen is stable - keep masks alive but don't scan
                    consecutiveNoChangeFrames++
                    framesSinceLastScan++

                    // Reset reference if stable for too long (prevents drift)
                    if (consecutiveNoChangeFrames >= STABILITY_RESET_THRESHOLD) {
                        resetChangeDetectionReference()
                        consecutiveNoChangeFrames = 0
                    }

                    // Force scan periodically even if "stable" (safety net)
                    if (framesSinceLastScan > MAX_FRAMES_WITHOUT_SCAN) {
                        Log.d(TAG, "⏰ Forced scan after $framesSinceLastScan stable frames")
                        processFrameAndReset(currentBitmap)
                    } else {
                        keepMemoriesAlive()
                        currentBitmap.recycle()
                    }
                }

                changeResult == ChangeType.MASKED_ONLY -> {
                    // Only masked areas changed - ignore (likely mask flicker)
                    Log.d(TAG, "🎭 Masked-only change detected - skipping scan")
                    consecutiveNoChangeFrames++
                    framesSinceLastScan++
                    keepMemoriesAlive()
                    currentBitmap.recycle()
                }

                changeResult == ChangeType.SIGNIFICANT_CHANGE -> {
                    // Real content change detected
                    consecutiveNoChangeFrames = 0
                    processFrameAndReset(currentBitmap)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Capture failed", e)
        } finally {
            image.close()
        }
    }

    private enum class ChangeType {
        NO_CHANGE,
        MASKED_ONLY,
        SIGNIFICANT_CHANGE
    }

    /**
     * ENHANCED DRIFT CORRECTION v2 - OPTIMIZED:
     * - Expands mask regions to avoid edge detection
     * - Tracks masked vs unmasked changes separately
     * - Uses stability counter to prevent drift accumulation
     * - More conservative thresholds
     * - OPTIMIZED: Early exit when enough unmasked changes found
     */
    private fun hasScreenChanged(currentBitmap: Bitmap): ChangeType {
        signatureCanvas.drawBitmap(currentBitmap, null, signatureRectDest, signaturePaint)
        signatureBitmap.getPixels(currentSignaturePixels, 0, SIGNATURE_SIZE, 0, 0, SIGNATURE_SIZE, SIGNATURE_SIZE)

        var totalSignificantChanges = 0
        var unmaskedChanges = 0

        // Use much larger expansion when masks exist
        val expansion = if (trackedMasks.isNotEmpty()) 60 else MASK_EXPANSION_PIXELS  // 60px buffer!
        val expandedMasks = synchronized(trackedMasks) {
            trackedMasks.map { expandRect(it.rect, expansion) }
        }
        val hasActiveMasks = expandedMasks.isNotEmpty()

        previousSignaturePixels?.let { prev ->
            for (i in currentSignaturePixels.indices) {
                val currPixel = currentSignaturePixels[i]
                val prevPixel = prev[i]

                val rDiff = abs(Color.red(currPixel) - Color.red(prevPixel))
                val gDiff = abs(Color.green(currPixel) - Color.green(prevPixel))
                val bDiff = abs(Color.blue(currPixel) - Color.blue(prevPixel))

                if (rDiff > PIXEL_DIFF_THRESHOLD || gDiff > PIXEL_DIFF_THRESHOLD || bDiff > PIXEL_DIFF_THRESHOLD) {
                    totalSignificantChanges++

                    val x = i % SIGNATURE_SIZE
                    val y = i / SIGNATURE_SIZE
                    val screenX = (x.toFloat() / SIGNATURE_SIZE) * captureWidth
                    val screenY = (y.toFloat() / SIGNATURE_SIZE) * captureHeight

                    var isMasked = false
                    for (mask in expandedMasks) {
                        if (mask.contains(screenX.toInt(), screenY.toInt())) {
                            isMasked = true
                            break
                        }
                    }

                    if (!isMasked) {
                        unmaskedChanges++
                    }
                }
            }
        }

        val changeType = when {
            previousSignaturePixels == null -> {
                Log.d(TAG, "📊 First frame - forcing initial scan")
                ChangeType.SIGNIFICANT_CHANGE
            }
            hasActiveMasks -> {
                // Extremely high bars when content is masked
                if (unmaskedChanges > 100 || totalSignificantChanges > 1000) {  // Very tolerant
                    Log.d(TAG, "📊 Active masks: REAL global change (unmasked=$unmaskedChanges, total=$totalSignificantChanges) → SCAN")
                    ChangeType.SIGNIFICANT_CHANGE
                } else {
                    Log.d(TAG, "🎭 Active masks: jitter/masked only (unmasked=$unmaskedChanges) → SKIP")
                    ChangeType.NO_CHANGE
                }
            }
            else -> {
                if (unmaskedChanges > 40 || totalSignificantChanges > 400) {
                    ChangeType.SIGNIFICANT_CHANGE
                } else {
                    ChangeType.NO_CHANGE
                }
            }
        }

        // Always update reference
        resetChangeDetectionReference()

        if (changeType == ChangeType.SIGNIFICANT_CHANGE) {
            consecutiveNoChangeFrames = 0
            framesSinceLastScan = 0
        } else {
            consecutiveNoChangeFrames++
            framesSinceLastScan++
        }

        return changeType
    }

    /**
     * Helper to expand a rectangle by N pixels in all directions
     */
    private fun expandRect(rect: Rect, pixels: Int): Rect {
        return Rect(
            (rect.left - pixels).coerceAtLeast(0),
            (rect.top - pixels).coerceAtLeast(0),
            (rect.right + pixels).coerceAtMost(captureWidth),
            (rect.bottom + pixels).coerceAtMost(captureHeight)
        )
    }

    /**
     * Reset change detection reference (prevents drift)
     */
    /**
     * Updated to properly initialize and maintain the reference signature
     * without losing it unnecessarily
     */
    private fun resetChangeDetectionReference() {
        if (previousSignaturePixels == null) {
            // First time: allocate and copy
            previousSignaturePixels = currentSignaturePixels.copyOf()
        } else {
            // Subsequent calls: copy current into previous
            System.arraycopy(currentSignaturePixels, 0, previousSignaturePixels!!, 0, currentSignaturePixels.size)
        }
    }

    private fun processFrameAndReset(bitmap: Bitmap) {
        framesSinceLastScan = 0
        resetChangeDetectionReference()

        val processingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()

        isBusy.set(true)
        mlExecutor.execute { processFrame(processingBitmap, currentOcrTask.incrementAndGet()) }
    }

    private fun processFrame(bitmap: Bitmap, taskId: Int) {
        try {
            if (isClosed.get()) { isBusy.set(false); bitmap.recycle(); return }

            val visualRects = try {
                toxicDetector?.analyzeWithScreenDimensions(bitmap, captureWidth, captureHeight) ?: emptyList()
            } catch (e: Exception) { emptyList() }

            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (taskId != currentOcrTask.get()) {
                        isBusy.set(false); bitmap.recycle(); return@addOnSuccessListener
                    }
                    val ocrRects = mutableListOf<Rect>()
                    for (block in visionText.textBlocks) {
                        if (analyzeText(block.text) && block.boundingBox != null) {
                            ocrRects.add(block.boundingBox!!)
                        }
                    }
                    updateMaskMemory(visualRects + ocrRects)
                    bitmap.recycle()
                    isBusy.set(false)
                }
                .addOnFailureListener {
                    updateMaskMemory(visualRects)
                    bitmap.recycle()
                    isBusy.set(false)
                }
        } catch (e: Exception) {
            bitmap.recycle()
            isBusy.set(false)
        }
    }

    private fun updateMaskMemory(newRawRects: List<Rect>) {
        val now = System.currentTimeMillis()
        val usedNewRects = BooleanArray(newRawRects.size)

        synchronized(trackedMasks) {
            val iterator = trackedMasks.iterator()
            while (iterator.hasNext()) {
                val tracker = iterator.next()
                var bestMatchIndex = -1

                for (i in newRawRects.indices) {
                    if (usedNewRects[i]) continue
                    if (areRectsClose(tracker.rect, newRawRects[i])) {
                        bestMatchIndex = i
                        break
                    }
                }

                if (bestMatchIndex != -1) {
                    tracker.lastSeenTime = now
                    tracker.rect = newRawRects[bestMatchIndex]
                    usedNewRects[bestMatchIndex] = true
                }
            }

            for (i in newRawRects.indices) {
                if (!usedNewRects[i]) {
                    trackedMasks.add(TrackedMask(newRawRects[i], now))
                }
            }

            trackedMasks.removeAll { (now - it.lastSeenTime) > MEMORY_DURATION_MS }
        }
        publishResults()
    }

    private fun keepMemoriesAlive() {
        val now = System.currentTimeMillis()
        synchronized(trackedMasks) {
            trackedMasks.forEach { it.lastSeenTime = now }
        }
    }

    private fun publishResults() {
        val output = synchronized(trackedMasks) {
            trackedMasks.map { Rect(it.rect) }
        }
        mainHandler?.post { onToxicRegionsDetected?.invoke(output) }
    }

    private fun areRectsClose(r1: Rect, r2: Rect): Boolean {
        val dx = abs(r1.centerX() - r2.centerX())
        val dy = abs(r1.centerY() - r2.centerY())
        return dx < 60 && dy < 60
    }

    private fun analyzeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return fastToxicKeywords.any { lower.contains(it) }
    }

    private fun setupVirtualDisplay() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds; captureWidth = bounds.width(); captureHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = wm.defaultDisplay
            val p = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(p)
            captureWidth = p.x; captureHeight = p.y
        }
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ handleImageAvailable(it) }, handler)
        virtualDisplay = mediaProjection?.createVirtualDisplay("SafeScreen", captureWidth, captureHeight, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler)
    }

    private fun releaseDisplayResources() {
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null

        // REMOVE THIS LINE — it was causing "First frame" resets
        // previousSignaturePixels = null

        synchronized(trackedMasks) { trackedMasks.clear() }
    }

    override fun close() {
        isClosed.set(true)
        releaseDisplayResources()
        synchronized(this) { toxicDetector?.close(); toxicDetector = null }
        mlExecutor.shutdownNow()
        textRecognizer.close()
        handlerThread?.quitSafely()
    }

    fun setDetectionPaused(paused: Boolean) {
        isPaused.set(paused)
    }

    fun startAudioMonitoringWithRetry() {}
}