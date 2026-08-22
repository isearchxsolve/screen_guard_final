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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ToxicDetector(context: Context) : AutoCloseable {
    private val TAG = "ToxicDetector"
    private var interpreter: Interpreter? = null

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
        // MATCHED to Python script (0.70)
        private const val CONFIDENCE_THRESHOLD = 0.50f
        private const val IOU_THRESHOLD = 0.70f
        private const val CONTAINMENT_THRESHOLD = 0.70f
        private const val MIN_BOX_SIZE_RATIO = 0.05f
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
                setUseNNAPI(false)
            }
            interpreter = Interpreter(buffer, options)

            // Pre-allocate buffers
            val inputBufferSize = 1 * INPUT_SIZE * INPUT_SIZE * 3 * 4
            reusableInputBuffer = ByteBuffer.allocateDirect(inputBufferSize)
                .order(ByteOrder.nativeOrder())

            val outputBufferSize = 1 * OUTPUT_CHANNELS * OUTPUT_ANCHORS * 4
            reusableOutputBuffer = ByteBuffer.allocateDirect(outputBufferSize)
                .order(ByteOrder.nativeOrder())

            reusableOutputArray = FloatArray(1 * OUTPUT_CHANNELS * OUTPUT_ANCHORS)
            reusableIntValues = IntArray(INPUT_SIZE * INPUT_SIZE)

            Log.i(TAG, "✅ ToxicDetector initialized")
            Log.i(TAG, "   - Confidence threshold: $CONFIDENCE_THRESHOLD")
            Log.i(TAG, "   - Input size: $INPUT_SIZE")
            Log.i(TAG, "   - Threads: 4")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing TFLite", e)
        }
    }

    fun analyze(original: Bitmap, onResult: (List<Rect>) -> Unit) {
        analysisCount++
        Log.v(TAG, "🔬 Analysis #$analysisCount: ${original.width}x${original.height}")

        if (interpreter == null) {
            Log.e(TAG, "❌ Analysis #$analysisCount FAILED: interpreter is NULL")
            onResult(emptyList())
            return
        }

        try {
            // 1. Preprocess
            val (scale, padX, padY) = preprocess(original)

            // 2. Inference
            reusableInputBuffer!!.rewind()
            reusableOutputBuffer!!.rewind()
            interpreter!!.run(reusableInputBuffer, reusableOutputBuffer)

            // 3. Extract output
            reusableOutputBuffer!!.rewind()
            reusableOutputBuffer!!.asFloatBuffer().get(reusableOutputArray)

            // 4. Postprocess
            val results = postprocess(
                reusableOutputArray!!,
                original.width,
                original.height,
                scale,
                padX,
                padY
            )

            Log.d(TAG, "✅ Analysis #$analysisCount: ${results.size} regions detected")
            onResult(results)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Analysis #$analysisCount error", e)
            onResult(emptyList())
        }
    }

    private fun preprocess(bitmap: Bitmap): Triple<Float, Int, Int> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val scale = min(INPUT_SIZE.toFloat() / h, INPUT_SIZE.toFloat() / w)
        val newW = (w * scale).roundToInt()
        val newH = (h * scale).roundToInt()

        val padLeft = (INPUT_SIZE - newW) / 2
        val padTop = (INPUT_SIZE - newH) / 2

        // Prepare reusable bitmap
        if (reusableInputBitmap == null || reusableInputBitmap!!.width != INPUT_SIZE) {
            reusableInputBitmap?.recycle()
            reusableInputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(reusableInputBitmap!!)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        canvas.drawBitmap(scaled, padLeft.toFloat(), padTop.toFloat(), paint)
        scaled.recycle()

        // Extract and convert to BGR [0, 1]
        reusableInputBitmap!!.getPixels(reusableIntValues!!, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        reusableInputBuffer!!.rewind()

        val pixels = reusableIntValues!!
        val buffer = reusableInputBuffer!!

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            buffer.putFloat(b / 255.0f)
            buffer.putFloat(g / 255.0f)
            buffer.putFloat(r / 255.0f)
        }

        return Triple(scale, padLeft, padTop)
    }

    private fun postprocess(
        data: FloatArray,
        origW: Int,
        origH: Int,
        scale: Float,
        padX: Int,
        padY: Int
    ): List<Rect> {
        val boxes = ArrayList<Rect>(64)
        val confidences = ArrayList<Float>(64)
        val minPixelSize = INPUT_SIZE * MIN_BOX_SIZE_RATIO

        for (i in 0 until OUTPUT_ANCHORS) {
            val conf = data[4 * OUTPUT_ANCHORS + i]

            if (conf > CONFIDENCE_THRESHOLD) {
                var cx = data[0 * OUTPUT_ANCHORS + i]
                var cy = data[1 * OUTPUT_ANCHORS + i]
                var w  = data[2 * OUTPUT_ANCHORS + i]
                var h  = data[3 * OUTPUT_ANCHORS + i]

                // Normalization fix
                if (w < 2.0f && h < 2.0f) {
                    cx *= INPUT_SIZE
                    cy *= INPUT_SIZE
                    w *= INPUT_SIZE
                    h *= INPUT_SIZE
                }

                if (w < minPixelSize || h < minPixelSize) continue

                val xUnpad = (cx - w / 2f) - padX
                val yUnpad = (cy - h / 2f) - padY
                val x2Unpad = (cx + w / 2f) - padX
                val y2Unpad = (cy + h / 2f) - padY

                val x1 = (xUnpad / scale).roundToInt().coerceIn(0, origW)
                val y1 = (yUnpad / scale).roundToInt().coerceIn(0, origH)
                val x2 = (x2Unpad / scale).roundToInt().coerceIn(0, origW)
                val y2 = (y2Unpad / scale).roundToInt().coerceIn(0, origH)

                if (x2 > x1 && y2 > y1) {
                    boxes.add(Rect(x1, y1, x2, y2))
                    confidences.add(conf)
                }
            }
        }

        if (boxes.isEmpty()) return emptyList()

        val nmsBoxes = nms(boxes, confidences)
        return filterContainedBoxes(nmsBoxes)
    }

    private fun nms(boxes: ArrayList<Rect>, scores: ArrayList<Float>): List<Rect> {
        val indices = scores.indices.sortedByDescending { scores[it] }
        val active = BooleanArray(boxes.size) { true }
        val result = ArrayList<Rect>(boxes.size)

        for (i in indices) {
            if (!active[i]) continue
            result.add(boxes[i])

            for (j in indices) {
                if (i != j && active[j]) {
                    if (calculateIoU(boxes[i], boxes[j]) > IOU_THRESHOLD) {
                        active[j] = false
                    }
                }
            }
        }
        return result
    }

    private fun filterContainedBoxes(boxes: List<Rect>): List<Rect> {
        val sorted = boxes.sortedByDescending { it.width() * it.height() }
        val finalBoxes = ArrayList<Rect>(boxes.size)

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
        val interLeft = max(inner.left, outer.left)
        val interTop = max(inner.top, outer.top)
        val interRight = min(inner.right, outer.right)
        val interBottom = min(inner.bottom, outer.bottom)

        if (interLeft < interRight && interTop < interBottom) {
            val interArea = (interRight - interLeft) * (interBottom - interTop)
            val innerArea = inner.width() * inner.height()
            return innerArea > 0 && (interArea.toFloat() / innerArea) > CONTAINMENT_THRESHOLD
        }
        return false
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
        Log.i(TAG, "ToxicDetector closing (analyzed $analysisCount frames)")
        interpreter?.close()
        reusableInputBitmap?.recycle()
        reusableInputBitmap = null
        reusableInputBuffer = null
        reusableOutputBuffer = null
        reusableOutputArray = null
    }
}