package com.safescreen.guard.audio

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ✅ UltraLowLatencyProfanityDetector - FIXED
 *
 * CRITICAL FIXES:
 * 1. 🔥 Google censors profanity - detect censored patterns (s***, f***, etc.)
 * 2. 🛑 Stop continuous restarts when no speech detected
 * 3. ⚡ Only restart after ACTUAL speech ends
 */
class UltraLowLatencyProfanityDetector(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    private val TAG = "UltraLatency"

    // ═══════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════

    private var speechRecognizer: SpeechRecognizer? = null
    private val isRunning = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private val processingThread = HandlerThread("ProfanityProcessor",
        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
    private lateinit var processingHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private var consecutiveFailures = 0
    private val MAX_FAILURES = 5
    @Volatile var isDisabled = false
        private set

    private var onProfanityDetected: ((word: String, fullText: String) -> Unit)? = null
    private var shouldContinueListening = false
    private var lastRestartTime = 0L
    private val MIN_RESTART_DELAY_MS = 1000L

    // 🛑 CRITICAL: Track if speech was actually detected
    private var speechDetectedInSession = false

    // ⚡ DEDUPLICATION
    private var lastDetectedWord = ""
    private var lastDetectionTime = 0L
    private val DUPLICATE_THRESHOLD_MS = 800L

    // ═══════════════════════════════════════════════════════════════
    // 🔥 CENSORSHIP PATTERNS - Google replaces profanity with asterisks
    // ═══════════════════════════════════════════════════════════════

    private val censorPatterns = listOf(
        // Single letter + asterisks (most common)
        Regex("""\bs\*+""", RegexOption.IGNORE_CASE),      // shit
        Regex("""\bf\*+""", RegexOption.IGNORE_CASE),      // fuck
        Regex("""\bb\*+""", RegexOption.IGNORE_CASE),      // bitch
        Regex("""\bc\*+""", RegexOption.IGNORE_CASE),      // cunt/crap
        Regex("""\bd\*+""", RegexOption.IGNORE_CASE),      // damn/dick
        Regex("""\bp\*+""", RegexOption.IGNORE_CASE),      // pussy
        Regex("""\ba\*+\s*h""", RegexOption.IGNORE_CASE),  // a** hole

        // Two letters + asterisks
        Regex("""\bsh\*+""", RegexOption.IGNORE_CASE),     // shit
        Regex("""\bfu\*+""", RegexOption.IGNORE_CASE),     // fuck
        Regex("""\bpu\*+""", RegexOption.IGNORE_CASE),     // pussy
        Regex("""\bbi\*+""", RegexOption.IGNORE_CASE),     // bitch
        Regex("""\bas\*+""", RegexOption.IGNORE_CASE),     // ass

        // Three letters + asterisks
        Regex("""\bshi\*+""", RegexOption.IGNORE_CASE),    // shit
        Regex("""\bfuc\*+""", RegexOption.IGNORE_CASE),    // fuck
        Regex("""\bass\*+""", RegexOption.IGNORE_CASE),    // asshole

        // Standalone asterisks
        Regex("""\b\*{2,}\b"""),                            // ***
        Regex("""\b\*+\s+\w+""")                           // *** you
    )

    // Map censored patterns to actual words (for logging)
    private val censorToWord = mapOf(
        "s***" to "shit", "s**" to "shit", "sh**" to "shit", "shi*" to "shit",
        "f***" to "fuck", "f**" to "fuck", "fu**" to "fuck", "fuc*" to "fuck",
        "b****" to "bitch", "b***" to "bitch", "bi**" to "bitch",
        "a**" to "ass", "as*" to "ass",
        "d***" to "damn", "d**" to "damn",
        "c***" to "cunt", "c**" to "crap"
    )

    // Direct profanity (in case Google doesn't censor)
    private val profanitySet = setOf(
        "shit", "fuck", "fucking", "fucker", "fucked", "motherfucker",
        "ass", "asshole", "bastard", "bitch",
        "damn", "dammit", "crap",
        "dick", "cock", "pussy", "cunt",
        "whore", "slut", "hoe",
        "nigger", "nigga", "faggot", "fag", "retard", "retarded"
    )

    private val safeWords = setOf(
        "glass", "pass", "class", "mass", "bass",
        "assassin", "assume", "assist", "classic"
    )

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    init {
        processingThread.start()
        processingHandler = Handler(processingThread.looper)
        initializeSpeechRecognizer()
        Log.i(TAG, "✅ UltraLowLatencyProfanityDetector initialized")
    }

    private fun initializeSpeechRecognizer() {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.e(TAG, "❌ Speech recognition not available")
                    isDisabled = true
                    return@post
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())

                isInitialized.set(true)
                Log.i(TAG, "✅ SpeechRecognizer ready")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Init failed: ${e.message}", e)
                isDisabled = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RECOGNITION LISTENER - FIXED
    // ═══════════════════════════════════════════════════════════════

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                consecutiveFailures = 0
                speechDetectedInSession = false  // 🛑 Reset
                Log.d(TAG, "🎤 Ready")
            }

            override fun onBeginningOfSpeech() {
                speechDetectedInSession = true  // 🛑 CRITICAL: Speech started
                lastDetectedWord = ""
                Log.d(TAG, "🗣️ Speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "🔇 Speech ended")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e(TAG, "❌ No microphone permission")
                        isDisabled = true
                        stopMonitoring()
                        return
                    }
                    else -> "Error $error"
                }

                // 🛑 Don't restart if no speech was detected
                if (error == SpeechRecognizer.ERROR_NO_MATCH && !speechDetectedInSession) {
                    Log.v(TAG, "⏭️ No speech - waiting for next session")
                    scheduleRestart(delayMs = 2000L)  // Longer delay
                    return
                }

                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && !speechDetectedInSession) {
                    Log.v(TAG, "⏭️ Timeout with no speech - waiting")
                    scheduleRestart(delayMs = 2000L)
                    return
                }

                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Log.w(TAG, "⚠️ Error: $errorMsg")
                    handleFailure()
                }

                scheduleRestart()
            }

            override fun onResults(results: Bundle?) {
                consecutiveFailures = 0

                processingHandler.post {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.d(TAG, "📝 FINAL: \"$text\"")
                        processTextFast(text, isFinal = true)
                    }
                }

                // 🛑 Only restart if speech was detected
                if (speechDetectedInSession) {
                    scheduleRestart()
                } else {
                    scheduleRestart(delayMs = 2000L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                processingHandler.post {
                    val matches = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        Log.v(TAG, "📄 Partial: \"$text\"")
                        processTextFast(text, isFinal = false)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ⚡ DETECTION LOGIC - CENSORSHIP AWARE
    // ═══════════════════════════════════════════════════════════════

    private fun processTextFast(text: String, isFinal: Boolean) {
        if (text.isBlank()) return

        val startTime = System.nanoTime()

        // 🔥 STRATEGY 1: Check for CENSORED patterns (Google's asterisks)
        for (pattern in censorPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val detectionTime = (System.nanoTime() - startTime) / 1_000_000
                val censoredWord = match.value.lowercase()
                val actualWord = censorToWord[censoredWord] ?: "profanity"

                Log.w(TAG, "🔥 CENSORED DETECTED in ${detectionTime}ms: \"$censoredWord\" -> $actualWord")
                Log.w(TAG, "   Full text: \"$text\"")
                triggerAlert(actualWord, text)
                return
            }
        }

        // ⚡ STRATEGY 2: Direct profanity check (uncensored)
        val words = text.lowercase().split(Regex("[\\s,.!?;:'\"]+"))

        for (word in words) {
            if (word.length < 3) continue
            if (safeWords.contains(word)) continue

            if (profanitySet.contains(word)) {
                val detectionTime = (System.nanoTime() - startTime) / 1_000_000
                Log.w(TAG, "⚡ PROFANITY in ${detectionTime}ms: \"$word\"")
                triggerAlert(word, text)
                return
            }

            // Prefix match for compound words
            if (word.length in 4..10) {
                for (profanity in profanitySet) {
                    if (profanity.length >= 4 && word.startsWith(profanity)) {
                        val detectionTime = (System.nanoTime() - startTime) / 1_000_000
                        Log.w(TAG, "⚡ COMPOUND in ${detectionTime}ms: \"$profanity\" in \"$word\"")
                        triggerAlert(profanity, text)
                        return
                    }
                }
            }
        }
    }

    private fun triggerAlert(word: String, fullText: String) {
        val now = System.currentTimeMillis()

        if (word == lastDetectedWord &&
            now - lastDetectionTime < DUPLICATE_THRESHOLD_MS) {
            Log.v(TAG, "⏭️ Skipped duplicate: \"$word\"")
            return
        }

        lastDetectedWord = word
        lastDetectionTime = now

        mainHandler.post {
            Log.w(TAG, "🚨🚨🚨 ALERT: \"$word\" in \"$fullText\"")
            onProfanityDetected?.invoke(word, fullText)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RESTART LOGIC - FIXED
    // ═══════════════════════════════════════════════════════════════

    private fun scheduleRestart(delayMs: Long = 300L) {
        if (!shouldContinueListening || isDisabled) return

        val now = System.currentTimeMillis()
        val timeSinceLastRestart = now - lastRestartTime

        val finalDelay = if (timeSinceLastRestart < MIN_RESTART_DELAY_MS) {
            MIN_RESTART_DELAY_MS - timeSinceLastRestart
        } else {
            delayMs
        }

        mainHandler.postDelayed({
            if (shouldContinueListening && !isDisabled) {
                lastRestartTime = System.currentTimeMillis()
                startListeningInternal()
            }
        }, finalDelay)
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    fun startMonitoring(onProfanityDetected: (word: String, fullText: String) -> Unit) {
        if (isDisabled) {
            Log.e(TAG, "Detector is disabled")
            return
        }

        if (isRunning.get()) {
            Log.w(TAG, "Already monitoring")
            return
        }

        this.onProfanityDetected = onProfanityDetected
        this.shouldContinueListening = true
        isRunning.set(true)

        mainHandler.post {
            lastRestartTime = System.currentTimeMillis()
            startListeningInternal()
        }

        Log.i(TAG, "⚡ Started ULTRA-LOW-LATENCY monitoring")
        Log.i(TAG, "   Target: <100ms detection latency")
        Log.i(TAG, "🔥 CENSORSHIP DETECTION ENABLED")
    }

    private fun startListeningInternal() {
        if (!isInitialized.get() || !shouldContinueListening || isDisabled) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

                // Balanced timing
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    500L
                )
            }

            speechRecognizer?.startListening(intent)
            Log.v(TAG, "👂 Listening...")

        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}")
            handleFailure()
        }
    }

    fun stopMonitoring() {
        shouldContinueListening = false
        isRunning.set(false)

        mainHandler.removeCallbacksAndMessages(null)
        processingHandler.removeCallbacksAndMessages(null)

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Stop error: ${e.message}")
            }
        }

        Log.i(TAG, "🛑 Monitoring stopped")
    }

    private fun handleFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_FAILURES) {
            Log.e(TAG, "❌ Disabled after $MAX_FAILURES failures")
            isDisabled = true
            stopMonitoring()
        }
    }

    fun close() {
        stopMonitoring()

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }

        processingThread.quitSafely()
        isInitialized.set(false)
        Log.i(TAG, "UltraLowLatencyProfanityDetector closed")
    }
}