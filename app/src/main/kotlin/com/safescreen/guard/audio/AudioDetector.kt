package com.safescreen.guard.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ✅ ROLLING WINDOW AudioDetector (Microphone)
 *
 * For microphone input only (not other app audio).
 * Uses rolling buffer with 250ms hop for mid-word detection.
 *
 * Use AudioPlaybackDetector instead if you need to capture other apps' audio.
 */
class AudioDetector(private val context: Context) {

    private val TAG = "AudioDetector"

    // ═══════════════════════════════════════════════════════════════
    // AUDIO CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    private val SAMPLE_RATE = 16000

    // YAMNet expects 15600 samples
    private val WINDOW_SIZE = 15600

    // Hop size: 250ms for rolling window
    private val HOP_SIZE = 4000

    private val NUM_CLASSES = 521

    // ═══════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════

    private val isRunning = AtomicBoolean(false)
    private var monitoringThread: Thread? = null
    private val rollingBuffer = FloatArray(WINDOW_SIZE)

    // ═══════════════════════════════════════════════════════════════
    // ML MODEL
    // ═══════════════════════════════════════════════════════════════

    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        interpreter = Interpreter(loadModelFile("yamnet_1.tflite"))
        labels = loadLabels("yamnet_label_list.txt")
        Log.i(TAG, "✅ YAMNet AudioDetector initialized (rolling window)")
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTINUOUS MONITORING (Rolling Window)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start continuous monitoring with rolling window.
     *
     * @param onDetection Called every ~250ms with (label, confidence)
     */
    fun startMonitoring(onDetection: (label: String, confidence: Float) -> Unit) {
        if (isRunning.get()) {
            Log.w(TAG, "Already monitoring")
            return
        }

        isRunning.set(true)

        monitoringThread = Thread({
            Log.i(TAG, "🎙️ Starting microphone rolling window")

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(bufferSize, HOP_SIZE * 4)
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "❌ AudioRecord init failed")
                isRunning.set(false)
                return@Thread
            }

            try {
                recorder.startRecording()

                val tempBuffer = ShortArray(HOP_SIZE)
                rollingBuffer.fill(0f)

                while (isRunning.get()) {
                    val read = recorder.read(tempBuffer, 0, HOP_SIZE)
                    if (read <= 0) {
                        Thread.sleep(10)
                        continue
                    }

                    // Slide buffer left
                    System.arraycopy(
                        rollingBuffer,
                        HOP_SIZE,
                        rollingBuffer,
                        0,
                        WINDOW_SIZE - HOP_SIZE
                    )

                    // Append new audio
                    for (i in 0 until read) {
                        rollingBuffer[WINDOW_SIZE - HOP_SIZE + i] = tempBuffer[i] / 32768f
                    }

                    // Run inference
                    val result = runInference()
                    if (result != null) {
                        onDetection(result.first, result.second)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Monitoring error: ${e.message}", e)
            } finally {
                recorder.stop()
                recorder.release()
                Log.i(TAG, "🛑 Microphone monitoring stopped")
            }
        }, "AudioDetectorRolling")

        monitoringThread?.start()
    }

    fun stopMonitoring() {
        isRunning.set(false)
        monitoringThread?.interrupt()
        monitoringThread = null
    }

    // ═══════════════════════════════════════════════════════════════
    // SINGLE-SHOT DETECTION (Legacy)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Single detection - records 1 second and classifies.
     * For legacy compatibility.
     */
    fun detectOnce(): String? {
        val audioBuffer = recordAudio() ?: return null

        val result = runInferenceOn(audioBuffer)
        return if (result != null && result.second > 0.6f) {
            result.first
        } else {
            null
        }
    }

    private fun recordAudio(): FloatArray? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "❌ AudioRecord init failed")
            return null
        }

        val audioData = ShortArray(WINDOW_SIZE)
        recorder.startRecording()
        recorder.read(audioData, 0, audioData.size)
        recorder.stop()
        recorder.release()

        val floatData = FloatArray(WINDOW_SIZE)
        for (i in audioData.indices) {
            floatData[i] = audioData[i] / 32768.0f
        }

        return floatData
    }

    // ═══════════════════════════════════════════════════════════════
    // INFERENCE
    // ═══════════════════════════════════════════════════════════════

    private fun runInference(): Pair<String, Float>? {
        return runInferenceOn(rollingBuffer)
    }

    private fun runInferenceOn(audioBuffer: FloatArray): Pair<String, Float>? {
        return try {
            val input = arrayOf(audioBuffer)
            val output = Array(1) { FloatArray(NUM_CLASSES) }

            interpreter.run(input, output)

            val scores = output[0]
            val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: return null
            val confidence = scores[maxIdx]

            if (maxIdx < labels.size) {
                labels[maxIdx] to confidence
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        val inputStream = fileDescriptor.createInputStream()
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
        )
    }

    private fun loadLabels(filename: String): List<String> {
        val labels = mutableListOf<String>()
        val reader = BufferedReader(InputStreamReader(context.assets.open(filename)))
        reader.useLines { lines ->
            lines.forEach { labels.add(it) }
        }
        return labels
    }

    fun close() {
        stopMonitoring()
        interpreter.close()
    }
}