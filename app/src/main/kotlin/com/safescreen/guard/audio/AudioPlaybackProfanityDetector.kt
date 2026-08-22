package com.safescreen.guard.audio

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * ⚡ AudioPlaybackProfanityDetector v10 - FIXED MODEL COMPATIBILITY
 *
 * KEY FIX: Restored correct input shapes for profanity_context_detector.tflite
 * - Context: 59 frames (1.2s) - MATCHES MODEL
 * - Current: 37 frames (0.4s) - MATCHES MODEL
 * - But optimized processing: Every 20ms instead of 100ms
 * - Immediate beep on first detection (no confirmation delay)
 *
 * Expected latency: ~200-300ms from word start to beep
 */
class AudioPlaybackProfanityDetector(
    private val context: Context,
    private val onDetected: (String) -> Unit
) {

    companion object {
        private const val TAG = "PlaybackKWS"

        // ═══════════════════════════════════════════════════════════════
        // AUDIO CONFIG - MUST MATCH MODEL EXACTLY
        // ═══════════════════════════════════════════════════════════════

        private const val SAMPLE_RATE = 16000

        // Context window - RESTORED to match model
        private const val CONTEXT_WINDOW_SEC = 1.2f
        private const val CONTEXT_WINDOW_SAMPLES = (SAMPLE_RATE * CONTEXT_WINDOW_SEC).toInt()  // 19200

        // Current window - RESTORED to match model
        private const val CURRENT_WINDOW_SEC = 0.40f
        private const val CURRENT_WINDOW_SAMPLES = (SAMPLE_RATE * CURRENT_WINDOW_SEC).toInt()  // 6400

        // Total buffer
        private const val TOTAL_BUFFER_SAMPLES = CONTEXT_WINDOW_SAMPLES + CURRENT_WINDOW_SAMPLES  // 25600

        // Mel config for context - MATCHES MODEL
        private const val CONTEXT_N_FFT = 512
        private const val CONTEXT_HOP = 320
        private const val CONTEXT_N_MELS = 32
        private const val CONTEXT_N_FRAMES = 59  // RESTORED - model expects 59

        // Mel config for current - MATCHES MODEL
        private const val CURRENT_N_FFT = 512
        private const val CURRENT_HOP = 160
        private const val CURRENT_N_MELS = 40
        private const val CURRENT_N_FRAMES = 37  // RESTORED - model expects 37

        // Detection thresholds - LOWERED for faster response
        private const val THRESHOLD = 0.55f           // Was 0.55f
        private const val HIGH_CONFIDENCE = 0.70f     // Was 0.75f

        // Cooldown
        private const val COOLDOWN_MS = 1500L
        private const val SUPPRESSION_MS = 800L       // REDUCED from 1000ms

        // Processing interval - OPTIMIZED
        private const val PROCESS_INTERVAL_MS = 20    // Was 100ms - 5x faster!
        private const val READ_CHUNK_SAMPLES = 320     // 20ms chunks

        // Mel frequency range
        private const val F_MIN = 80f
        private const val F_MAX = 7500f
    }

    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private var processingJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private var lastDetectionTime = 0L

    // ═══════════════════════════════════════════════════════════════
    // AUDIO BUFFERS
    // ═══════════════════════════════════════════════════════════════

    private val audioBuffer = FloatArray(TOTAL_BUFFER_SAMPLES)
    private var bufferWritePos = 0
    private var bufferFilled = false

    // Pre-allocated feature buffers
    private val contextMelSpec = Array(CONTEXT_N_FRAMES) { FloatArray(CONTEXT_N_MELS) }
    private val currentMelSpec = Array(CURRENT_N_FRAMES) { FloatArray(CURRENT_N_MELS) }

    // FFT buffers
    private val contextWindowedFrame = FloatArray(CONTEXT_N_FFT)
    private val currentWindowedFrame = FloatArray(CURRENT_N_FFT)
    private val contextPowerSpectrum = FloatArray(CONTEXT_N_FFT / 2 + 1)
    private val currentPowerSpectrum = FloatArray(CURRENT_N_FFT / 2 + 1)

    // Pre-computed windows and filterbanks
    private val contextHannWindow by lazy { createHannWindow(CONTEXT_N_FFT) }
    private val currentHannWindow by lazy { createHannWindow(CURRENT_N_FFT) }
    private val contextMelFilterbank by lazy { createMelFilterbank(CONTEXT_N_FFT, CONTEXT_N_MELS) }
    private val currentMelFilterbank by lazy { createMelFilterbank(CURRENT_N_FFT, CURRENT_N_MELS) }

    // Pre-computed twiddle factors
    private val contextTwiddleReal by lazy { createTwiddleReal(CONTEXT_N_FFT) }
    private val contextTwiddleImag by lazy { createTwiddleImag(CONTEXT_N_FFT) }
    private val currentTwiddleReal by lazy { createTwiddleReal(CURRENT_N_FFT) }
    private val currentTwiddleImag by lazy { createTwiddleImag(CURRENT_N_FFT) }

    // Model I/O buffers - CORRECT SIZES
    private val contextInputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * CONTEXT_N_FRAMES * CONTEXT_N_MELS * 1)  // 1888 bytes
            .order(ByteOrder.nativeOrder())
    }
    private val currentInputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * CURRENT_N_FRAMES * CURRENT_N_MELS * 1)  // 1480 bytes
            .order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = Array(1) { ByteArray(1) }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    fun start(projection: MediaProjection) {
        if (isRunning.getAndSet(true)) return

        loadModel()
        initAudio(projection)
    }

    fun stop() {
        isRunning.set(false)
        processingJob?.cancel()
        processingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        try {
            interpreter?.close()
        } catch (_: Exception) {}

        audioRecord = null
        interpreter = null

        Log.i(TAG, "🛑 Detector stopped")
    }

    // ═══════════════════════════════════════════════════════════════
    // MODEL LOADING
    // ═══════════════════════════════════════════════════════════════

    private fun loadModel() {
        try {
            val modelName = listOf(
                "profanity_context_detector.tflite",
                "profanity_onset_detector.tflite",
                "profanity_kws_16k_int8.tflite"
            ).firstOrNull { name ->
                try {
                    context.assets.open(name).close()
                    true
                } catch (e: Exception) {
                    false
                }
            } ?: throw Exception("No model found")

            val modelBytes = context.assets.open(modelName).readBytes()
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
                setNumThreads(2)
            })

            Log.i(TAG, "✔ Model loaded: $modelName")

            interpreter?.let { interp ->
                val inputCount = interp.inputTensorCount
                Log.i(TAG, "   Inputs: $inputCount")
                for (i in 0 until inputCount) {
                    val shape = interp.getInputTensor(i).shape()
                    Log.i(TAG, "   Input[$i]: ${shape.contentToString()}")
                }
            }

            Log.i(TAG, "   Context: $CONTEXT_N_FRAMES frames (${CONTEXT_WINDOW_SEC}s)")
            Log.i(TAG, "   Current: $CURRENT_N_FRAMES frames (${CURRENT_WINDOW_SEC}s)")
            Log.i(TAG, "   Process interval: ${PROCESS_INTERVAL_MS}ms (OPTIMIZED)")
            Log.i(TAG, "   Threshold: $THRESHOLD (LOWERED for faster response)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Model load failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIO CAPTURE
    // ═══════════════════════════════════════════════════════════════

    private fun initAudio(projection: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setSampleRate(SAMPLE_RATE)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "⚠️ AudioRecord init failed, retrying...")
                mainHandler.postDelayed({ initAudio(projection) }, 300)
                return
            }

            audioRecord!!.startRecording()
            startProcessingLoop()

            Log.i(TAG, "🎧 Detection running with 20ms processing interval")
            Log.i(TAG, "   Expected latency: ~200-300ms from word start to beep")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Audio init failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROCESSING LOOP - OPTIMIZED TO 20ms
    // ═══════════════════════════════════════════════════════════════

    private fun startProcessingLoop() {
        processingJob = CoroutineScope(Dispatchers.Default).launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val readBuffer = ShortArray(READ_CHUNK_SAMPLES)
            var samplesSinceLastProcess = 0
            val processIntervalSamples = SAMPLE_RATE * PROCESS_INTERVAL_MS / 1000

            while (isActive && isRunning.get()) {
                try {
                    val samplesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0

                    if (samplesRead > 0) {
                        // Add to circular buffer
                        for (i in 0 until samplesRead) {
                            audioBuffer[bufferWritePos] = readBuffer[i] / 32768f
                            bufferWritePos = (bufferWritePos + 1) % TOTAL_BUFFER_SAMPLES
                        }

                        // Mark buffer as filled
                        if (!bufferFilled && bufferWritePos < samplesRead) {
                            bufferFilled = true
                            Log.d(TAG, "📦 Buffer filled, detection active (20ms intervals)")
                        }

                        samplesSinceLastProcess += samplesRead

                        // Process every 20ms (5x faster than before)
                        if (bufferFilled && samplesSinceLastProcess >= processIntervalSamples) {
                            samplesSinceLastProcess = 0

                            val detected = processWithContext()
                            if (detected) {
                                // Clear buffer to prevent multiple detections
                                clearBuffer()
                            }
                        }
                    }

                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Read error: ${e.message}")
                        delay(10)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DETECTION PROCESSING
    // ═══════════════════════════════════════════════════════════════

    private fun processWithContext(): Boolean {
        val interp = interpreter ?: return false
        val now = System.currentTimeMillis()

        // Skip during suppression (REDUCED from 1000ms to 800ms)
        if (now - lastDetectionTime < SUPPRESSION_MS) {
            return false
        }

        // Calculate buffer positions
        val currentEnd = bufferWritePos
        val currentStart = (currentEnd - CURRENT_WINDOW_SAMPLES + TOTAL_BUFFER_SAMPLES) % TOTAL_BUFFER_SAMPLES
        val contextStart = (currentStart - CONTEXT_WINDOW_SAMPLES + TOTAL_BUFFER_SAMPLES) % TOTAL_BUFFER_SAMPLES

        // Quick energy check on current window
        var sumSq = 0f
        for (i in 0 until CURRENT_WINDOW_SAMPLES) {
            val sample = audioBuffer[(currentStart + i) % TOTAL_BUFFER_SAMPLES]
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / CURRENT_WINDOW_SAMPLES)

        if (rms < 0.015f) return false  // Silence (LOWERED from 0.02f)

        // Extract features
        extractContextFeatures(contextStart)
        extractCurrentFeatures(currentStart)

        // Prepare model inputs
        prepareContextInput()
        prepareCurrentInput()

        // Run inference
        return try {
            val inputCount = interp.inputTensorCount

            if (inputCount == 2) {
                // Two-input model (context-aware)
                val inputs = arrayOf(contextInputBuffer, currentInputBuffer)
                val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)
                interp.runForMultipleInputsOutputs(inputs, outputs)
            } else {
                // Single-input model (fallback)
                interp.run(currentInputBuffer, outputBuffer)
            }

            // Dequantize output
            val rawOutput = outputBuffer[0][0].toInt() and 0xFF
            val score = rawOutput / 255f

            // Check thresholds - IMMEDIATE beep on detection
            when {
                score >= HIGH_CONFIDENCE -> {
                    triggerDetection(score, "high_conf")
                    true
                }
                score >= THRESHOLD && (now - lastDetectionTime > COOLDOWN_MS) -> {
                    triggerDetection(score, "threshold")
                    true
                }
                else -> false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            false
        }
    }

    private fun triggerDetection(score: Float, reason: String) {
        lastDetectionTime = System.currentTimeMillis()

        Log.w(TAG, "🚨 PROFANITY DETECTED! Score: ${"%.3f".format(score)} ($reason)")

        mainHandler.post {
            onDetected("profanity")
        }
    }

    private fun clearBuffer() {
        // Only clear the current window, keep context for next detection
        val clearStart = (bufferWritePos - CURRENT_WINDOW_SAMPLES + TOTAL_BUFFER_SAMPLES) % TOTAL_BUFFER_SAMPLES
        for (i in 0 until CURRENT_WINDOW_SAMPLES) {
            audioBuffer[(clearStart + i) % TOTAL_BUFFER_SAMPLES] = 0f
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════

    private fun extractContextFeatures(startIdx: Int) {
        var frameIdx = 0
        var sampleOffset = 0

        while (frameIdx < CONTEXT_N_FRAMES && sampleOffset + CONTEXT_N_FFT <= CONTEXT_WINDOW_SAMPLES) {
            for (i in 0 until CONTEXT_N_FFT) {
                val idx = (startIdx + sampleOffset + i) % TOTAL_BUFFER_SAMPLES
                contextWindowedFrame[i] = audioBuffer[idx] * contextHannWindow[i]
            }

            computePowerSpectrum(contextWindowedFrame, contextPowerSpectrum,
                contextTwiddleReal, contextTwiddleImag, CONTEXT_N_FFT)

            for (m in 0 until CONTEXT_N_MELS) {
                var sum = 0f
                for (k in 0 until CONTEXT_N_FFT / 2 + 1) {
                    sum += contextMelFilterbank[m][k] * contextPowerSpectrum[k]
                }
                contextMelSpec[frameIdx][m] = 10f * log10(max(sum, 1e-10f))
            }

            frameIdx++
            sampleOffset += CONTEXT_HOP
        }

        while (frameIdx < CONTEXT_N_FRAMES) {
            contextMelSpec[frameIdx].fill(-80f)
            frameIdx++
        }
    }

    private fun extractCurrentFeatures(startIdx: Int) {
        var frameIdx = 0
        var sampleOffset = 0

        while (frameIdx < CURRENT_N_FRAMES && sampleOffset + CURRENT_N_FFT <= CURRENT_WINDOW_SAMPLES) {
            for (i in 0 until CURRENT_N_FFT) {
                val idx = (startIdx + sampleOffset + i) % TOTAL_BUFFER_SAMPLES
                currentWindowedFrame[i] = audioBuffer[idx] * currentHannWindow[i]
            }

            computePowerSpectrum(currentWindowedFrame, currentPowerSpectrum,
                currentTwiddleReal, currentTwiddleImag, CURRENT_N_FFT)

            for (m in 0 until CURRENT_N_MELS) {
                var sum = 0f
                for (k in 0 until CURRENT_N_FFT / 2 + 1) {
                    sum += currentMelFilterbank[m][k] * currentPowerSpectrum[k]
                }
                currentMelSpec[frameIdx][m] = 10f * log10(max(sum, 1e-10f))
            }

            frameIdx++
            sampleOffset += CURRENT_HOP
        }

        while (frameIdx < CURRENT_N_FRAMES) {
            currentMelSpec[frameIdx].fill(-80f)
            frameIdx++
        }
    }

    private fun prepareContextInput() {
        contextInputBuffer.rewind()

        var maxVal = -Float.MAX_VALUE
        for (frame in 0 until CONTEXT_N_FRAMES) {
            for (mel in 0 until CONTEXT_N_MELS) {
                if (contextMelSpec[frame][mel] > maxVal) maxVal = contextMelSpec[frame][mel]
            }
        }

        for (frame in 0 until CONTEXT_N_FRAMES) {
            for (mel in 0 until CONTEXT_N_MELS) {
                val normalized = if (maxVal > -80f) {
                    ((contextMelSpec[frame][mel] - maxVal + 80f) / 80f * 2f - 1f).coerceIn(-1f, 1f)
                } else {
                    -1f
                }
                contextInputBuffer.put((normalized * 127).toInt().coerceIn(-128, 127).toByte())
            }
        }
        contextInputBuffer.rewind()
    }

    private fun prepareCurrentInput() {
        currentInputBuffer.rewind()

        var maxVal = -Float.MAX_VALUE
        for (frame in 0 until CURRENT_N_FRAMES) {
            for (mel in 0 until CURRENT_N_MELS) {
                if (currentMelSpec[frame][mel] > maxVal) maxVal = currentMelSpec[frame][mel]
            }
        }

        for (frame in 0 until CURRENT_N_FRAMES) {
            for (mel in 0 until CURRENT_N_MELS) {
                val normalized = if (maxVal > -80f) {
                    ((currentMelSpec[frame][mel] - maxVal + 80f) / 80f * 2f - 1f).coerceIn(-1f, 1f)
                } else {
                    -1f
                }
                currentInputBuffer.put((normalized * 127).toInt().coerceIn(-128, 127).toByte())
            }
        }
        currentInputBuffer.rewind()
    }

    // ═══════════════════════════════════════════════════════════════
    // DSP UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun computePowerSpectrum(
        frame: FloatArray,
        spectrum: FloatArray,
        twiddleReal: FloatArray,
        twiddleImag: FloatArray,
        nfft: Int
    ) {
        for (k in 0 until nfft / 2 + 1) {
            var real = 0f
            var imag = 0f

            for (n in 0 until nfft) {
                val angle = (k * n) % nfft
                real += frame[n] * twiddleReal[angle]
                imag += frame[n] * twiddleImag[angle]
            }

            spectrum[k] = real * real + imag * imag
        }
    }

    private fun createHannWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            (0.5f * (1 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
    }

    private fun createTwiddleReal(size: Int): FloatArray {
        return FloatArray(size) { k -> cos(-2.0 * PI * k / size).toFloat() }
    }

    private fun createTwiddleImag(size: Int): FloatArray {
        return FloatArray(size) { k -> sin(-2.0 * PI * k / size).toFloat() }
    }

    private fun createMelFilterbank(nfft: Int, nMels: Int): Array<FloatArray> {
        val filterbank = Array(nMels) { FloatArray(nfft / 2 + 1) }

        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        val melPoints = FloatArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }

        val fftFreqs = FloatArray(nfft / 2 + 1) { k ->
            k * SAMPLE_RATE.toFloat() / nfft
        }

        for (m in 0 until nMels) {
            val fLow = melPoints[m]
            val fCenter = melPoints[m + 1]
            val fHigh = melPoints[m + 2]

            for (k in 0 until nfft / 2 + 1) {
                val freq = fftFreqs[k]
                filterbank[m][k] = when {
                    freq < fLow -> 0f
                    freq < fCenter -> (freq - fLow) / (fCenter - fLow)
                    freq < fHigh -> (fHigh - freq) / (fHigh - fCenter)
                    else -> 0f
                }
            }
        }

        return filterbank
    }
}