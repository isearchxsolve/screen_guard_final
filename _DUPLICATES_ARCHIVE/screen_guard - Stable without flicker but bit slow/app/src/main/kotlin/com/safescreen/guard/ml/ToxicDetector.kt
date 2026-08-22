package com.safescreen.guard.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.max

class ToxicDetector(context: Context) : AutoCloseable {
    private val TAG = "ToxicDetector"
    private var interpreter: Interpreter? = null
    private val interpreterLock = Any()  // Thread-safe access

    private val INPUT_SIZE = 640
    private val OUTPUT_ANCHORS = 8400
    private val OUTPUT_CHANNELS = 5

    // Reusable resources
    private var reusableInputBitmap: Bitmap? = null
    private var reusableIntValues: IntArray? = null
    private var reusableInputBuffer: ByteBuffer? = null
    private var reusableOutputBuffer: ByteBuffer? = null
    private var reusableOutputArray: FloatArray? = null

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.55f
        private const val IOU_THRESHOLD = 0.70f
        private const val MIN_BOX_SIZE_RATIO = 0.08f
    }

    private var analysisCount = 0

    init {
        try {
            val fd = context.assets.openFd("best_float16.tflite")
            val stream = FileInputStream(fd.fileDescriptor)
            val channel = stream.channel
            val startOffset = fd.startOffset
            val declaredLength = fd.declaredLength
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // NNAPI disabled for stability
            }
            interpreter = Interpreter(buffer, options)

            // Pre-allocate buffers
            val inputBufferSize = 1 * INPUT_SIZE * INPUT_SIZE * 3 * 4
            reusableInputBuffer = ByteBuffer.allocateDirect(inputBufferSize).order(ByteOrder.nativeOrder())

            val outputBufferSize = 1 * OUTPUT_CHANNELS * OUTPUT_ANCHORS * 4
            reusableOutputBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())

            reusableOutputArray = FloatArray(1 * OUTPUT_CHANNELS * OUTPUT_ANCHORS)
            reusableIntValues = IntArray(INPUT_SIZE * INPUT_SIZE)

            Log.i(TAG, "✅ ToxicDetector initialized with CPU/4 Threads")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing TFLite", e)
            interpreter = null
        }
    }

    /**
     * OPTIMIZED: Analyze bitmap that's already scaled to ~640x640 from DetectionEngine
     * This avoids double-scaling overhead
     */
    fun analyzeWithScreenDimensions(original: Bitmap, screenWidth: Int, screenHeight: Int): List<Rect> {
        analysisCount++

        if (interpreter == null || reusableInputBuffer == null || reusableOutputBuffer == null) {
            return emptyList()
        }

        if (original.isRecycled) {
            return emptyList()
        }

        try {
            // OPTIMIZATION: Check if bitmap is already correct size
            val needsResize = original.width != INPUT_SIZE || original.height != INPUT_SIZE

            val (scale, padX, padY) = if (needsResize) {
                preprocess(original)
            } else {
                // Bitmap is already 640x640, just convert to buffer
                preprocessOptimized(original)
                Triple(1.0f, 0, 0)
            }

            val input = reusableInputBuffer ?: return emptyList()
            val output = reusableOutputBuffer ?: return emptyList()
            val outputArray = reusableOutputArray ?: return emptyList()

            input.rewind()
            output.rewind()

            // CRITICAL: Thread-safe interpreter access
            synchronized(interpreterLock) {
                interpreter?.run(input, output)
            }

            output.rewind()
            output.asFloatBuffer().get(outputArray)

            // Scale results back to screen space
            val results = postprocess(
                outputArray,
                screenWidth,
                screenHeight,
                scale,
                padX,
                padY,
                original.width,
                original.height
            )

            return results

        } catch (e: Exception) {
            Log.e(TAG, "❌ Analysis #$analysisCount failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * OPTIMIZED: Direct preprocessing for already-sized bitmaps
     */
    private fun preprocessOptimized(bitmap: Bitmap): Boolean {
        if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            return false
        }

        val pixels = reusableIntValues ?: return false
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buffer = reusableInputBuffer ?: return false
        buffer.rewind()

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        return true
    }

    private fun preprocess(bitmap: Bitmap): Triple<Float, Int, Int> {
        if (bitmap.isRecycled) throw IllegalStateException("Bitmap is recycled")

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val scale = min(INPUT_SIZE.toFloat() / h, INPUT_SIZE.toFloat() / w)
        val newW = (w * scale).roundToInt()
        val newH = (h * scale).roundToInt()

        val padLeft = (INPUT_SIZE - newW) / 2
        val padTop = (INPUT_SIZE - newH) / 2

        if (reusableInputBitmap == null || reusableInputBitmap!!.width != INPUT_SIZE) {
            reusableInputBitmap?.recycle()
            reusableInputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(reusableInputBitmap!!)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } catch (e: OutOfMemoryError) {
            return Triple(scale, padLeft, padTop)
        }

        canvas.drawBitmap(scaled, padLeft.toFloat(), padTop.toFloat(), paint)
        scaled.recycle()

        val pixels = reusableIntValues ?: return Triple(scale, padLeft, padTop)
        reusableInputBitmap!!.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val buffer = reusableInputBuffer ?: return Triple(scale, padLeft, padTop)
        buffer.rewind()

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        return Triple(scale, padLeft, padTop)
    }

    private fun postprocess(
        data: FloatArray,
        targetWidth: Int,
        targetHeight: Int,
        scale: Float,
        padX: Int,
        padY: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<Rect> {
        val boxes = ArrayList<Rect>()
        val confidences = ArrayList<Float>()
        val minPixelSize = INPUT_SIZE * MIN_BOX_SIZE_RATIO

        for (i in 0 until OUTPUT_ANCHORS) {
            val conf = data[4 * OUTPUT_ANCHORS + i]
            if (conf > CONFIDENCE_THRESHOLD) {
                val cx = data[i]
                val cy = data[OUTPUT_ANCHORS + i]
                val w = data[2 * OUTPUT_ANCHORS + i]
                val h = data[3 * OUTPUT_ANCHORS + i]

                val finalW = if (w < 2.0f) w * INPUT_SIZE else w
                val finalH = if (h < 2.0f) h * INPUT_SIZE else h
                val finalCx = if (cx < 2.0f) cx * INPUT_SIZE else cx
                val finalCy = if (cy < 2.0f) cy * INPUT_SIZE else cy

                if (finalW < minPixelSize || finalH < minPixelSize) continue

                val xUnpad = (finalCx - finalW / 2f) - padX
                val yUnpad = (finalCy - finalH / 2f) - padY
                val x2Unpad = (finalCx + finalW / 2f) - padX
                val y2Unpad = (finalCy + finalH / 2f) - padY

                val xBitmap = xUnpad / scale
                val yBitmap = yUnpad / scale
                val x2Bitmap = x2Unpad / scale
                val y2Bitmap = y2Unpad / scale

                if (bitmapWidth > 0 && bitmapHeight > 0) {
                    val scaleX = targetWidth.toFloat() / bitmapWidth
                    val scaleY = targetHeight.toFloat() / bitmapHeight

                    val x1 = (xBitmap * scaleX).roundToInt().coerceIn(0, targetWidth)
                    val y1 = (yBitmap * scaleY).roundToInt().coerceIn(0, targetHeight)
                    val x2 = (x2Bitmap * scaleX).roundToInt().coerceIn(0, targetWidth)
                    val y2 = (y2Bitmap * scaleY).roundToInt().coerceIn(0, targetHeight)

                    if (x2 > x1 && y2 > y1) {
                        boxes.add(Rect(x1, y1, x2, y2))
                        confidences.add(conf)
                    }
                } else {
                    val x1 = xBitmap.roundToInt().coerceIn(0, targetWidth)
                    val y1 = yBitmap.roundToInt().coerceIn(0, targetHeight)
                    val x2 = x2Bitmap.roundToInt().coerceIn(0, targetWidth)
                    val y2 = y2Bitmap.roundToInt().coerceIn(0, targetHeight)

                    if (x2 > x1 && y2 > y1) {
                        boxes.add(Rect(x1, y1, x2, y2))
                        confidences.add(conf)
                    }
                }
            }
        }

        if (boxes.isEmpty()) return emptyList()
        return filterContainedBoxes(nms(boxes, confidences))
    }

    private fun nms(boxes: ArrayList<Rect>, scores: ArrayList<Float>): List<Rect> {
        if (boxes.isEmpty()) return emptyList()

        val indices = scores.indices.sortedByDescending { scores[it] }
        val active = BooleanArray(boxes.size) { true }
        val result = ArrayList<Rect>()

        for (i in indices) {
            if (!active[i]) continue
            result.add(boxes[i])
            if (result.size >= 15) break

            for (j in indices) {
                if (i != j && active[j] && calculateIoU(boxes[i], boxes[j]) > IOU_THRESHOLD) {
                    active[j] = false
                }
            }
        }
        return result
    }

    private fun filterContainedBoxes(boxes: List<Rect>): List<Rect> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.width() * it.height() }
        val finalBoxes = ArrayList<Rect>()

        for (box in sorted) {
            var isRedundant = false
            for (bigBox in finalBoxes) {
                if (isRectContained(box, bigBox)) {
                    isRedundant = true
                    break
                }
            }
            if (!isRedundant) finalBoxes.add(box)
        }
        return finalBoxes
    }

    private fun isRectContained(inner: Rect, outer: Rect): Boolean {
        return inner.left >= outer.left && inner.top >= outer.top &&
                inner.right <= outer.right && inner.bottom <= outer.bottom
    }

    private fun calculateIoU(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        if (l >= r || t >= bot) return 0f
        val inter = (r - l) * (bot - t).toFloat()
        val union = (a.width() * a.height()) + (b.width() * b.height()) - inter
        return if (union > 0) inter / union else 0f
    }

    override fun close() {
        Log.i(TAG, "ToxicDetector closing")
        synchronized(interpreterLock) {
            interpreter?.close()
            interpreter = null
        }
        reusableInputBitmap?.recycle()
        reusableInputBitmap = null
        reusableInputBuffer = null
        reusableOutputBuffer = null
        reusableIntValues = null
        reusableOutputArray = null
    }
}