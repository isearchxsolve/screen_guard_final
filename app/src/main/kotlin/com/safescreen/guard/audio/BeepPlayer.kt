package com.safescreen.guard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.util.Log

/**
 * ✅ BeepPlayer v4 - FIXED Cooldown
 *
 * Key fixes:
 * 1. Reduced cooldown for profanity alerts (300ms instead of 500ms)
 * 2. Better logging to debug beep behavior
 * 3. Separate cooldowns for different alert types
 */
object BeepPlayer {

    private val TAG = "BeepPlayer"

    // ═══════════════════════════════════════════════════════════════
    // AUDIO PLAYERS
    // ═══════════════════════════════════════════════════════════════

    private var soundPool: SoundPool? = null
    private var beepSoundId: Int = 0
    private var warningSoundId: Int = 0
    private var isPoolReady = false

    private var toneGenerator: ToneGenerator? = null

    // ═══════════════════════════════════════════════════════════════
    // COOLDOWN (prevent audio spam) - FIXED
    // ═══════════════════════════════════════════════════════════════

    private var lastBeepTime = 0L
    private const val MIN_BEEP_INTERVAL_MS = 300L  // General beeps

    private var lastWarningTime = 0L
    private const val MIN_WARNING_INTERVAL_MS = 300L  // ✅ REDUCED from 500ms

    // Track last profanity to avoid duplicate beeps for same word
    private var lastProfanityWord = ""
    private var lastProfanityTime = 0L

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    fun initialize(context: Context) {
        if (soundPool != null) return

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(attributes)
                .build()
                .apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) {
                            when (sampleId) {
                                beepSoundId -> Log.d(TAG, "Beep sound loaded")
                                warningSoundId -> Log.d(TAG, "Warning sound loaded")
                            }
                            isPoolReady = true
                        }
                    }
                }

            loadSoundsFromAssets(context)

            Log.i(TAG, "✅ BeepPlayer initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
        }
    }

    private fun loadSoundsFromAssets(context: Context) {
        try {
            val afd = context.assets.openFd("beep.wav")
            beepSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
            Log.d(TAG, "Loaded custom beep.wav")
        } catch (e: Exception) {
            Log.d(TAG, "No custom beep.wav, using ToneGenerator")
        }

        try {
            val afd = context.assets.openFd("warning.wav")
            warningSoundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
            Log.d(TAG, "Loaded custom warning.wav")
        } catch (e: Exception) {
            Log.d(TAG, "No custom warning.wav, using ToneGenerator")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLAYBACK
    // ═══════════════════════════════════════════════════════════════

    fun beep() {
        val now = System.currentTimeMillis()

        if (now - lastBeepTime < MIN_BEEP_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Beep skipped (cooldown)")
            return
        }
        lastBeepTime = now

        Log.d(TAG, "🔔 Playing beep")

        if (isPoolReady && beepSoundId > 0) {
            soundPool?.play(beepSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            return
        }

        playTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    fun playTone(
        toneType: Int = ToneGenerator.TONE_PROP_BEEP,
        durationMs: Int = 200
    ) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Tone playback failed: ${e.message}")
        }
    }

    fun alert() {
        val now = System.currentTimeMillis()
        if (now - lastBeepTime < MIN_BEEP_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Alert skipped (cooldown)")
            return
        }
        lastBeepTime = now

        Log.d(TAG, "⚠️ Playing alert")

        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Alert playback failed: ${e.message}")
        }
    }

    /**
     * ✅ FIXED: Warning tone with better cooldown logic
     */
    fun warning() {
        val now = System.currentTimeMillis()

        // Check cooldown
        if (now - lastWarningTime < MIN_WARNING_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Warning skipped (cooldown: ${now - lastWarningTime}ms)")
            return
        }
        lastWarningTime = now

        Log.w(TAG, "🚨 PLAYING WARNING BEEP")

        // Try SoundPool first
        if (isPoolReady && warningSoundId > 0) {
            soundPool?.play(warningSoundId, 1.0f, 1.0f, 2, 0, 1.0f)
            Log.d(TAG, "✅ Warning played (SoundPool)")
            return
        }

        // Fallback: distinctive warning tone
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 250)
            Log.d(TAG, "✅ Warning played (ToneGenerator)")
        } catch (e: Exception) {
            Log.e(TAG, "Warning playback failed: ${e.message}")
        }
    }

    /**
     * ✅ NEW: Profanity-specific alert with word tracking
     */
    fun profanityAlert(word: String = "") {
        val now = System.currentTimeMillis()

        // Skip if same word detected within 1 second
        if (word.isNotEmpty() &&
            word == lastProfanityWord &&
            now - lastProfanityTime < 1000L) {
            Log.v(TAG, "⏭️ Duplicate profanity skipped: \"$word\"")
            return
        }

        // Check general cooldown
        if (now - lastWarningTime < MIN_WARNING_INTERVAL_MS) {
            Log.v(TAG, "⏭️ Profanity beep skipped (cooldown)")
            return
        }

        lastWarningTime = now
        lastProfanityWord = word
        lastProfanityTime = now

        Log.w(TAG, "🚨🚨🚨 PROFANITY ALERT BEEP")

        try {
            // Double beep pattern for emphasis
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150)
                } catch (_: Exception) {}
            }, 200)

        } catch (e: Exception) {
            Log.e(TAG, "Profanity alert failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════

    fun release() {
        try {
            soundPool?.release()
        } catch (_: Exception) {}
        soundPool = null
        isPoolReady = false
        beepSoundId = 0
        warningSoundId = 0

        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null

        Log.i(TAG, "BeepPlayer released")
    }
}