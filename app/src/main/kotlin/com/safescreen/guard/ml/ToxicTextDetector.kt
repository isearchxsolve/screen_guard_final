package com.safescreen.guard.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR + Toxic Text Detector
 * Returns normalized boxes (0..1) for mask rendering.
 */
class ToxicTextDetector(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun detectToxicText(bitmap: Bitmap): List<RectF> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()

        val toxicRegions = mutableListOf<RectF>()

        for (block in result.textBlocks) {
            val text = block.text.lowercase()

            if (isToxic(text)) {
                val box = block.boundingBox ?: continue
                toxicRegions.add(
                    RectF(
                        box.left.toFloat() / bitmap.width,
                        box.top.toFloat() / bitmap.height,
                        box.right.toFloat() / bitmap.width,
                        box.bottom.toFloat() / bitmap.height
                    )
                )
            }
        }

        return toxicRegions
    }

    // Simple toxic text classifier (extend as needed)
    private fun isToxic(t: String): Boolean {
        val badWords = listOf(
            "fuck", "shit", "bitch", "asshole", "kill you",
            "suicide", "hate you", "rape", "nigger", "slut"
        )

        return badWords.any { t.contains(it) }
    }
}
