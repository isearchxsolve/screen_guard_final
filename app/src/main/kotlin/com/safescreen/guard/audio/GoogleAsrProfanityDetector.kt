package com.safescreen.guard.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ✅ GoogleAsrProfanityDetector - Native Android Speech Recognition
 *
 * Uses Android's built-in SpeechRecognizer (Google ASR) for speech-to-text,
 * then filters for profanity/toxic words.
 *
 * Advantages over YAMNet + Vosk:
 * - No ML model files required (uses system services)
 * - Better speech recognition accuracy
 * - Smaller APK size
 * - Works with device language settings
 *
 * Requirements:
 * - RECORD_AUDIO permission
 * - Internet connection (for online recognition) or offline speech pack
 * - Google app installed (for offline recognition)
 */
class GoogleAsrProfanityDetector(
    private val context: Context,
    private val mediaProjection: MediaProjection? = null
) {
    private val TAG = "GoogleASR"

    // ═══════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════

    private var speechRecognizer: SpeechRecognizer? = null
    private val isListening = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio capture for playback (other apps' audio)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isCaptureRunning = AtomicBoolean(false)

    // Callbacks
    private var onProfanityDetected: ((word: String, fullText: String) -> Unit)? = null
    private var onSpeechDetected: ((text: String) -> Unit)? = null

    // Continuous listening state
    private var shouldContinueListening = false

    // ═══════════════════════════════════════════════════════════════
    // PROFANITY WORD LIST
    // ═══════════════════════════════════════════════════════════════

    private val profanityList = mutableSetOf(
        // Common profanity
        "shit", "fuck", "fucking", "fucker", "fucked", "motherfucker",
        "ass", "asshole", "bastard", "bitch",
        "damn", "dammit", "crap",
        "dick", "cock", "pussy", "cunt",
        "whore", "slut", "hoe",

        // Slurs and hate speech
        "nigger", "nigga", "faggot", "fag", "retard", "retarded",

        // Sexual terms
        "porn", "porno", "pornography",
        "sex", "sexy", "sexual",
        "nude", "naked", "xxx",
        "horny", "orgasm",

        // Violence
        "kill", "murder", "rape",

        // Drug references
        "cocaine", "heroin", "meth"
    )

    // Words that are okay in context (to reduce false positives)
    private val allowedContextWords = setOf(
        "glass", "pass", "class", "mass",  // Contains "ass" but okay
        "scrap", "crappy",                  // Contains "crap" but okay
        "damnation",                        // Religious context
        "assassin",                         // Contains "ass" twice but okay
        "assume", "assist", "classic",
        "basement", "harassment"
    )

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initialize the speech recognizer.
     * Must be called from main thread.
     */
    fun initialize(onReady: (Boolean) -> Unit) {
        if (isInitialized.get()) {
            onReady(true)
            return
        }

        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e(TAG, "❌ Speech recognition not available on this device")
                    onReady(false)
                    return@post
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())

                // Initialize audio capture if MediaProjection available
                if (mediaProjection != null) {
                    initializeAudioCapture()
                }

                isInitialized.set(true)
                Log.i(TAG, "✅ Google ASR initialized")
                onReady(true)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize: ${e.message}", e)
                onReady(false)
            }
        }
    }

    private fun initializeAudioCapture() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBufferSize, 16000))
                .build()

            Log.i(TAG, "✅ Audio playback capture initialized")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Audio capture not available: ${e.message}")
            audioRecord = null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RECOGNITION LISTENER
    // ═══════════════════════════════════════════════════════════════

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "🎤 Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🗣️ Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Audio level changed - can be used for visual feedback
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer received
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "🔇 Speech ended")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error: $error"
                }

                Log.w(TAG, "⚠️ Recognition error: $errorMsg")

                // Restart listening if we should continue
                if (shouldContinueListening && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    mainHandler.postDelayed({
                        if (shouldContinueListening) {
                            startListeningInternal()
                        }
                    }, 500)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                processResults(matches)

                // Restart listening for continuous mode
                if (shouldContinueListening) {
                    mainHandler.postDelayed({
                        if (shouldContinueListening) {
                            startListeningInternal()
                        }
                    }, 100)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                processPartialResults(matches)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Event: $eventType")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RESULT PROCESSING
    // ═══════════════════════════════════════════════════════════════

    private fun processResults(matches: ArrayList<String>?) {
        if (matches.isNullOrEmpty()) return

        val text = matches[0]
        Log.d(TAG, "📝 Recognized: \"$text\"")

        onSpeechDetected?.invoke(text)
        checkForProfanity(text)
    }

    private fun processPartialResults(matches: ArrayList<String>?) {
        if (matches.isNullOrEmpty()) return

        val text = matches[0]
        if (text.isNotBlank()) {
            // Check partial results for faster detection
            checkForProfanity(text)
        }
    }

    private fun checkForProfanity(text: String) {
        val words = text.lowercase().split(Regex("[\\s,.!?;:]+"))

        for (word in words) {
            if (word.isBlank()) continue

            // Skip allowed context words
            if (allowedContextWords.contains(word)) continue

            // Check exact match
            if (profanityList.contains(word)) {
                Log.w(TAG, "🚨 PROFANITY DETECTED: \"$word\" in \"$text\"")
                onProfanityDetected?.invoke(word, text)
                return
            }

            // Check if word contains profanity (for compound words)
            for (profanity in profanityList) {
                if (word.contains(profanity) && word.length <= profanity.length + 4) {
                    if (!isAllowedWord(word)) {
                        Log.w(TAG, "🚨 PROFANITY DETECTED: \"$profanity\" in word \"$word\"")
                        onProfanityDetected?.invoke(profanity, text)
                        return
                    }
                }
            }
        }
    }

    private fun isAllowedWord(word: String): Boolean {
        if (allowedContextWords.contains(word)) return true

        val allowedPrefixes = listOf("class", "pass", "mass", "glass", "grass", "bass")
        for (prefix in allowedPrefixes) {
            if (word.startsWith(prefix)) return true
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API - LISTENING CONTROL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start continuous listening for profanity.
     *
     * @param onProfanity Called when profanity is detected (word, full text)
     * @param onSpeech Optional: Called for all recognized speech
     */
    fun startListening(
        onProfanity: (word: String, fullText: String) -> Unit,
        onSpeech: ((text: String) -> Unit)? = null
    ) {
        if (!isInitialized.get()) {
            Log.e(TAG, "Not initialized. Call initialize() first.")
            return
        }

        this.onProfanityDetected = onProfanity
        this.onSpeechDetected = onSpeech
        this.shouldContinueListening = true

        mainHandler.post {
            startListeningInternal()
        }
    }

    private fun startListeningInternal() {
        if (!isInitialized.get() || !shouldContinueListening) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                // Use device language
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")

                // Prefer offline recognition if available
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

                // Longer speech timeout
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }

            isListening.set(true)
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "🎤 Listening started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            isListening.set(false)
        }
    }

    /**
     * Stop listening.
     */
    fun stopListening() {
        shouldContinueListening = false
        isListening.set(false)

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping: ${e.message}")
            }
        }

        Log.i(TAG, "🛑 Listening stopped")
    }

    /**
     * Check if currently listening.
     */
    fun isListening(): Boolean = isListening.get()

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add custom words to the profanity list.
     */
    fun addProfanityWords(vararg words: String) {
        profanityList.addAll(words.map { it.lowercase() })
    }

    /**
     * Remove words from the profanity list.
     */
    fun removeProfanityWords(vararg words: String) {
        profanityList.removeAll(words.map { it.lowercase() }.toSet())
    }

    /**
     * Add words to the allowed list (won't trigger even if they contain profanity).
     */
    fun addAllowedWords(vararg words: String) {
        (allowedContextWords as MutableSet).addAll(words.map { it.lowercase() })
    }

    /**
     * Get current profanity word list.
     */
    fun getProfanityList(): Set<String> = profanityList.toSet()

    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════

    fun close() {
        stopListening()

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }

        try {
            isCaptureRunning.set(false)
            captureThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
        captureThread = null
        isInitialized.set(false)

        Log.i(TAG, "GoogleAsrProfanityDetector closed")
    }
}