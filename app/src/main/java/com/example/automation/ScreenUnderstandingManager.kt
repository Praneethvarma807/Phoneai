package com.example.automation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.example.ai.LocalLlmEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenAnalysisReport(
    val sourceUsed: String,
    val extractedText: String,
    val explanation: String,
    val suggestedActions: List<String>
)

class ScreenUnderstandingManager(
    private val context: Context,
    private val localLlmEngine: LocalLlmEngine
) {
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    @Volatile
    private var lastProjectionResultCode: Int? = null

    @Volatile
    private var lastProjectionData: Intent? = null

    fun setProjectionGrant(resultCode: Int, data: Intent) {
        lastProjectionResultCode = resultCode
        lastProjectionData = data
    }

    fun hasProjectionGrant(): Boolean = lastProjectionResultCode != null && lastProjectionData != null

    fun canAnalyzeWithoutProjectionDialog(): Boolean =
        hasProjectionGrant() || CommanderAccessibilityService.isConnected()

    suspend fun analyzeCurrentScreen(): Result<ScreenAnalysisReport> = withContext(Dispatchers.Default) {
        val accessibilitySnapshot = CommanderAccessibilityService.activeService.value?.captureScreenSnapshot()

        val resultCode = lastProjectionResultCode
        val resultData = lastProjectionData

        var ocrText = ""
        var sourceLabel = ""
        var timedOut = false
        var projectionFailed = false

        if (resultCode != null && resultData != null) {
            val deferred = CompletableDeferred<Bitmap?>()
            ScreenCaptureService.pendingCapture = deferred
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            }
            val started = runCatching {
                ContextCompat.startForegroundService(context, serviceIntent)
            }.isSuccess

            val bitmap = try {
                if (started) {
                    withTimeoutOrNull(12_000L) {
                        deferred.await()
                    }
                } else {
                    projectionFailed = true
                    null
                }
            } catch (ce: CancellationException) {
                if (ScreenCaptureService.pendingCapture === deferred) {
                    ScreenCaptureService.pendingCapture = null
                }
                throw ce
            }

            if (bitmap == null && started) {
                timedOut = ScreenCaptureService.pendingCapture === deferred
                if (!timedOut) {
                    projectionFailed = true
                }
                if (ScreenCaptureService.pendingCapture === deferred) {
                    ScreenCaptureService.pendingCapture = null
                }
            }

            // Clear single-use MediaProjection grant after capture attempt on Android 14+
            lastProjectionResultCode = null
            lastProjectionData = null

            if (bitmap != null) {
                try {
                    ocrText = runMlKitOcr(bitmap)
                    sourceLabel = if (ocrText.isNotBlank()) {
                        "MediaProjection + On-Device ML Kit OCR"
                    } else {
                        "MediaProjection Capture (No Latin text recognized)"
                    }
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        }

        val combinedText = buildString {
            if (ocrText.isNotBlank()) appendLine(ocrText)
            if (accessibilitySnapshot != null && accessibilitySnapshot.combinedText.isNotBlank()) {
                if (isNotBlank()) appendLine("\n--- Accessibility Tree ---")
                appendLine(accessibilitySnapshot.combinedText)
                if (sourceLabel.isBlank()) {
                    sourceLabel = "Accessibility Window Inspection"
                } else if (!sourceLabel.contains("Accessibility")) {
                    sourceLabel += " + Accessibility Tree"
                }
            }
        }.trim()

        if (combinedText.isBlank()) {
            val msg = when {
                timedOut ->
                    "Screen capture timed out after 12 seconds. Grant Screen Capture permission again or enable Accessibility Service."
                projectionFailed ->
                    "Screen capture session stopped or permission was revoked. Re-grant Screen Capture or enable Accessibility Service."
                else ->
                    "No readable text was found on screen (on-device OCR reads Latin script unless Accessibility exposes screen text). Enable Accessibility Service or open readable text."
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val activePkg = accessibilitySnapshot?.packageName.orEmpty().lowercase()
        val sensitivePackage = SENSITIVE_PACKAGE_KEYWORDS.any { activePkg.contains(it) }
        val containsOtpOrSecret = com.example.memory.MemorySecurityHelper.containsForbiddenSecret(combinedText)
        if (sensitivePackage || containsOtpOrSecret) {
            return@withContext Result.success(
                ScreenAnalysisReport(
                    sourceUsed = sourceLabel.ifBlank { "Privacy-Guarded Screen Inspection" },
                    extractedText = "[Sensitive banking, password, or OTP screen content withheld locally for your protection.]",
                    explanation = "Detected potentially sensitive banking, authentication, or OTP content on screen${if (activePkg.isNotBlank()) " ($activePkg)" else ""}. Content was withheld and not stored.",
                    suggestedActions = listOf("Navigate away from sensitive screen before scanning")
                )
            )
        }

        val suggestions = deriveSuggestedActions(combinedText, accessibilitySnapshot)
        val explanation = generateLocalExplanation(combinedText, accessibilitySnapshot?.packageName)

        Result.success(
            ScreenAnalysisReport(
                sourceUsed = sourceLabel.ifBlank { "Local On-Device Analysis" },
                extractedText = combinedText.take(2000),
                explanation = explanation,
                suggestedActions = suggestions
            )
        )
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        if (bitmap.isRecycled) {
            if (cont.isActive) cont.resume("")
            return@suspendCancellableCoroutine
        }
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrNull()
        if (image == null) {
            if (cont.isActive) cont.resume("")
            return@suspendCancellableCoroutine
        }
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (cont.isActive) cont.resume(visionText.text.trim())
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
    }

    private suspend fun generateLocalExplanation(text: String, packageName: String?): String {
        if (localLlmEngine.runtimeState.value.isReady && !localLlmEngine.runtimeState.value.commandOnlyMode) {
            val prompt = buildString {
                appendLine("Explain what is shown on this phone screen concisely in 2-3 sentences based only on this extracted text. Do not invent details.")
                if (!packageName.isNullOrBlank()) appendLine("Active app package: $packageName")
                appendLine(text.take(1400))
            }
            val llmResult = localLlmEngine.summarizeLocally(prompt)
            if (llmResult.isSuccess && !llmResult.getOrNull().isNullOrBlank()) {
                return llmResult.getOrThrow()
            }
        }

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val preview = lines.take(5).joinToString(" • ")
        val appNote = if (!packageName.isNullOrBlank()) "Active package: $packageName. " else ""
        return "${appNote}Captured ${lines.size} visible text lines locally. Key content: $preview"
    }

    private fun deriveSuggestedActions(
        text: String,
        snapshot: ScreenSnapshot?
    ): List<String> {
        val suggestions = mutableListOf<String>()
        val lower = text.lowercase()
        if (lower.contains("http") || lower.contains("www.")) {
            suggestions.add("Open detected web link")
        }
        if (snapshot != null && snapshot.editableFields.isNotEmpty()) {
            suggestions.add("Type into ${snapshot.editableFields.first()}")
        }
        if (snapshot != null && snapshot.clickableTargets.isNotEmpty()) {
            suggestions.add("Tap \"${snapshot.clickableTargets.first()}\"")
        }
        suggestions.add("Create a local note from this screen")
        return suggestions.distinct().take(4)
    }

    fun close() {
        runCatching { textRecognizer.close() }
    }

    companion object {
        private val SENSITIVE_PACKAGE_KEYWORDS = listOf(
            "bank", "pay", "wallet", "upi", "gpay", "phonepe", "paytm",
            "auth", "authenticator", "keepass", "1password", "bitwarden", "lastpass"
        )
    }
}
