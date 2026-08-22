package com.safescreen.guard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.util.Log
import kotlin.math.sin

/**
 * ⚡ InstantBeepPlayer v4 - ULTRA LOW LATENCY + EXTENDED MASKING
 *
 * Optimized for onset-based detection:
 * - LONGER beep (400ms) to mask entire profane word
 * - ToneGenerator for absolute minimum latency (~5ms)
 * - Proper cooldown matching detector
 * - Louder volume for better masking
 */
object InstantBeepPlayer {

    private val TAG = "InstantBeep"

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    private const val SAMPLE_RATE = 44100
    private const val BEEP_FREQUENCY = 1000    // 1kHz - clear and attention-grabbing
    private const val BEEP_DURATION_MS = 400   // EXTENDED from 150ms to mask full word

    // ═══════════════════════════════════════════════════════════════
    // AUDIO PLAYERS
    // ═══════════════════════════════════════════════════════════════

    private var audioTrack: AudioTrack? = null
    private var toneGenerator: ToneGenerator? = null
    private lateinit var beepBuffer: ShortArray
    private var isInitialized = false

    // ═══════════════════════════════════════════════════════════════
    // COOLDOWN - Matches detector's cooldown
    // ═══════════════════════════════════════════════════════════════

    private var lastBeepTime = 0L
    private const val MIN_BEEP_INTERVAL_MS = 1400L

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            // ToneGenerator - ABSOLUTE lowest latency (~5ms)
            // Using max volume for better masking
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

            // AudioTrack as backup with LOW_LATENCY flag
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            audioTrack = AudioTrack(
                attributes,
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            // Pre-generate LONGER beep buffer for better masking
            beepBuffer = generateTone(BEEP_FREQUENCY, BEEP_DURATION_MS)

            audioTrack?.play()
            isInitialized = true

            Log.i(TAG, "✅ InstantBeepPlayer initialized (${BEEP_DURATION_MS}ms beep)")

        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            // Ensure at least ToneGenerator works
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AUDIO GENERATION
    // ═══════════════════════════════════════════════════════════════

    private fun generateTone(frequency: Int, durationMs: Int): ShortArray {
        val samples = (SAMPLE_RATE * durationMs / 1000)
        val buffer = ShortArray(samples)

        val amplitude = Short.MAX_VALUE * 0.85  // 85% volume for loud masking
        val angularFrequency = 2.0 * Math.PI * frequency / SAMPLE_RATE
        val fadeLength = (samples * 0.05).toInt()  // Shorter fade for quicker attack

        for (i in buffer.indices) {
            val envelope = when {
                i < fadeLength -> i.toFloat() / fadeLength
                i > samples - fadeLength -> (samples - i).toFloat() / fadeLength
                else -> 1.0f
            }
            buffer[i] = (amplitude * envelope * sin(angularFrequency * i)).toInt().toShort()
        }

        return buffer
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Standard beep
     */
    fun beep() {
        val now = System.currentTimeMillis()
        if (now - lastBeepTime < MIN_BEEP_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Beep skipped (cooldown)")
            return
        }
        lastBeepTime = now

        playBeep()
    }

    /**
     * ⚡ PROFANITY ALERT - EXTENDED beep for full word masking
     *
     * Strategy:
     * - Detector triggers at word ONSET (first 150ms of profanity)
     * - This beep plays for 400ms to mask the entire word
     * - Most profane words are 300-500ms long
     * - With ~200ms detection latency, we start beeping at the "i" in "shit"
     *   and continue through the "t", effectively masking 2/3 of the word
     */
    fun profanityAlert() {
        val now = System.currentTimeMillis()

        // Check cooldown
        if (now - lastBeepTime < MIN_BEEP_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Profanity beep skipped (cooldown)")
            return
        }
        lastBeepTime = now

        Log.w(TAG, "🚨 PROFANITY ALERT")

        // Use ToneGenerator for INSTANT playback (~5ms latency)
        try {
            // TONE_CDMA_ALERT_CALL_GUARD: Clear, loud, attention-grabbing
            // Duration adjusted to 400ms for full word masking
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_DURATION_MS)
        } catch (e: Exception) {
            // Fallback to AudioTrack if ToneGenerator fails
            Log.w(TAG, "ToneGenerator failed, using AudioTrack: ${e.message}")
            playBeep()
        }
    }

    /**
     * Warning beep (same as profanity for simplicity)
     */
    fun warning() {
        profanityAlert()
    }

    private fun playBeep() {
        if (!isInitialized) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            } catch (_: Exception) {}
            return
        }

        try {
            audioTrack?.write(beepBuffer, 0, beepBuffer.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    fun resetSession() {
        lastBeepTime = 0L
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null

        isInitialized = false
        Log.i(TAG, "InstantBeepPlayer released")
    }
}