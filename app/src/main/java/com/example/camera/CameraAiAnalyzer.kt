package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.ai.LocalLlmEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class CameraAnalysisResult(
    val sourceLabel: String,
    val extractedText: String,
    val summary: String,
    val brightnessHint: String,
    val detectedEntities: List<String>
)

class CameraAiAnalyzer(
    private val context: Context,
    private val localLlmEngine: LocalLlmEngine
) {
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun analyzeUri(uri: Uri, userQuestion: String? = null): Result<CameraAnalysisResult> =
        withContext(Dispatchers.IO) {
            val bitmap = decodeBitmapFromUri(uri)
                ?: return@withContext Result.failure(IllegalStateException("Unable to decode selected image."))
            try {
                analyzeBitmap(
                    bitmap = bitmap,
                    sourceLabel = "Photo Picker + On-Device OCR",
                    userQuestion = userQuestion
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    suspend fun analyzeBitmap(
        bitmap: Bitmap,
        sourceLabel: String = "CameraX + On-Device Vision",
        userQuestion: String? = null
    ): Result<CameraAnalysisResult> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled) {
            return@withContext Result.failure(IllegalStateException("Captured frame was recycled."))
        }
        val brightness = estimateBrightnessLabel(bitmap)
        val extracted = runMlKitOcr(bitmap)
        val entities = extractActionableEntities(extracted)

        val summary = buildAnswer(
            extractedText = extracted,
            brightness = brightness,
            entities = entities,
            userQuestion = userQuestion
        )

        Result.success(
            CameraAnalysisResult(
                sourceLabel = sourceLabel,
                extractedText = extracted,
                summary = summary,
                brightnessHint = brightness,
                detectedEntities = entities
            )
        )
    }

    private suspend fun buildAnswer(
        extractedText: String,
        brightness: String,
        entities: List<String>,
        userQuestion: String?
    ): String {
        val question = userQuestion?.trim().orEmpty()
        if (localLlmEngine.runtimeState.value.isReady && !localLlmEngine.runtimeState.value.commandOnlyMode) {
            val prompt = buildString {
                appendLine("You are answering a user's question about a captured camera frame purely from on-device OCR text and image lighting telemetry. Never hallucinate unseen objects.")
                appendLine("Lighting telemetry: $brightness")
                if (question.isNotBlank()) appendLine("User question: $question")
                if (extractedText.isBlank()) {
                    appendLine("OCR detected no readable text in this image.")
                } else {
                    appendLine("Extracted text:\n${extractedText.take(1400)}")
                }
            }
            val llmRes = localLlmEngine.summarizeLocally(prompt)
            if (llmRes.isSuccess && !llmRes.getOrNull().isNullOrBlank()) {
                return llmRes.getOrThrow()
            }
        }

        if (extractedText.isBlank()) {
            return if (question.isNotBlank()) {
                "No readable Latin-script text was detected in the frame ($brightness). Load a multimodal local AI model or point the camera clearly at printed text to answer \"$question\"."
            } else {
                "No readable Latin-script text detected ($brightness). On-device image OCR reads Latin text; non-Latin Indic scripts on camera frames require clearer printed Latin labels or an accessibility text view."
            }
        }

        val lines = extractedText.lines().map { it.trim() }.filter { it.isNotBlank() }
        return buildString {
            if (question.isNotBlank()) {
                append("Based on ${lines.size} captured text line(s) ($brightness): ")
            } else {
                append("Read ${lines.size} line(s) locally ($brightness): ")
            }
            append(lines.take(4).joinToString(" • "))
            if (entities.isNotEmpty()) {
                append(" | Detected: ${entities.joinToString(", ")}")
            }
        }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val input = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
        if (input == null) {
            if (cont.isActive) cont.resume("")
            return@suspendCancellableCoroutine
        }
        textRecognizer.process(input)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result.text.trim())
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
    }

    @Suppress("DEPRECATION")
    private fun decodeBitmapFromUri(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val maxDim = 1600
                val w = info.size.width
                val h = info.size.height
                if (w > maxDim || h > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(w, h)
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    }.getOrNull()

    fun close() {
        runCatching { textRecognizer.close() }
    }

    companion object {
        internal fun estimateBrightnessLabel(bitmap: Bitmap): String {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return "Normal lighting"
            val stepX = (w / 16).coerceAtLeast(1)
            val stepY = (h / 16).coerceAtLeast(1)
            var sum = 0L
            var count = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val px = bitmap.getPixel(x, y)
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    sum += (299 * r + 587 * g + 114 * b) / 1000
                    count++
                    x += stepX
                }
                y += stepY
            }
            val avg = if (count > 0) sum / count else 128L
            return when {
                avg < 55 -> "Low light ($avg/255)"
                avg > 205 -> "Bright lighting ($avg/255)"
                else -> "Balanced lighting ($avg/255)"
            }
        }

        internal fun extractActionableEntities(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val found = mutableListOf<String>()
            Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE).find(text)?.let {
                found.add("URL: ${it.value.take(40)}")
            }
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(text)?.let {
                found.add("Email: ${it.value}")
            }
            Regex("(?:₹|\\$|€)\\s?\\d+(?:[.,]\\d{1,2})?").find(text)?.let {
                found.add("Amount: ${it.value}")
            }
            return found.distinct().take(3)
        }
    }
}
