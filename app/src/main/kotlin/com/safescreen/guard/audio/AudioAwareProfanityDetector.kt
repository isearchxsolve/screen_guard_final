package com.safescreen.guard.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.math.sqrt

class AudioAwareProfanityDetector(
    private val context: Context
) {

    companion object {
        private const val TAG = "AudioAware"
        private const val SAMPLE_RATE = 16000
        private const val RMS_TRIGGER = 1200
        private const val SILENCE_TIMEOUT_MS = 900L
        private const val COOLDOWN_MS = 1200L
    }

    private var audioRecord: AudioRecord? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastBeepTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceRunnable: Runnable? = null
    private var monitorThread: Thread? = null

    private val profanityWords = setOf(
        "shit",
        "fuck",
        "fucker",
        "bitch",
        "asshole",
        "bastard",
        "motherfucker"
    )

    // =========================================================
    // PUBLIC API
    // =========================================================

    fun startMonitoring(onDetected: (String) -> Unit) {
        initAudioRecord()
        initSpeechRecognizer(onDetected)
        startAudioMonitor()
        Log.i(TAG, "✔ AudioAwareProfanityDetector initialized")
    }

    fun stop() {
        try {
            monitorThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()

            mainHandler.post {
                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // =========================================================
    // AUDIO MONITOR
    // =========================================================

    private fun initAudioRecord() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
    }

    private fun startAudioMonitor() {
        monitorThread = Thread {
            val buffer = ShortArray(1024)

            while (!Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read <= 0) continue

                val rms = calculateRMS(buffer, read)

                if (rms > RMS_TRIGGER && !isListening) {
                    // ✔ FIX: SpeechRecognizer MUST be called on main thread
                    mainHandler.post { startASR() }
                }
            }
        }.apply { start() }
    }

    private fun calculateRMS(data: ShortArray, size: Int): Int {
        var sum = 0.0
        for (i in 0 until size) {
            sum += data[i] * data[i]
        }
        return sqrt(sum / size).toInt()
    }

    // =========================================================
    // SPEECH RECOGNITION
    // =========================================================

    private fun initSpeechRecognizer(onDetected: (String) -> Unit) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle) {
                isListening = false

                val text = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )?.firstOrNull()?.lowercase() ?: return

                profanityWords.firstOrNull { text.contains(it) }?.let { foundWord ->
                    val now = System.currentTimeMillis()
                    if (now - lastBeepTime >= COOLDOWN_MS) {
                        lastBeepTime = now
                        onDetected(foundWord)
                        Log.w(TAG, "🚨 Profanity detected: $foundWord")
                    }
                }
            }

            override fun onError(error: Int) {
                isListening = false
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startASR() {
        if (isListening) return
        isListening = true

        silenceRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceRunnable = Runnable { stopASR() }
        mainHandler.postDelayed(silenceRunnable!!, SILENCE_TIMEOUT_MS)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        // ✔ MUST be on main thread — now guaranteed
        speechRecognizer?.startListening(intent)
    }

    private fun stopASR() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            isListening = false
        }
    }
}
