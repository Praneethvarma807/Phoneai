package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Pure Kotlin Wake Word phrase & acoustic gate logic for "Hello Buddy"
 * Tested directly in JVM unit tests without hardware mic dependencies.
 */
object WakeWordMatcher {
    private val exactAndPhoneticVariants = setOf(
        "hello buddy",
        "helo buddy",
        "halo buddy",
        "hello body",
        "hello badi",
        "helo badi",
        "halo badi",
        "hello budy",
        "helo budy",
        "ello buddy",
        "hello birdie",
        "hello buddie",
        "హలో బడ్డీ",
        "హలో బడి",
        "हेलो बडी",
        "हेलो बड्डी",
        "ஹலோ பட்டி",
        "ಹಲೋ ಬಡ್ಡಿ",
        "ഹലോ ബഡ്ഡി"
    )

    fun matchesTranscript(transcript: String, sensitivity: Float = 0.65f): Boolean {
        val normalized = transcript.lowercase()
            .replace(Regex("[^a-z\\u0900-\\u0D7F\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        if (exactAndPhoneticVariants.any { normalized.contains(it) }) return true

        val threshold = when {
            sensitivity >= 0.8f -> 0.72f
            sensitivity >= 0.5f -> 0.80f
            else -> 0.88f
        }
        val words = normalized.split(" ")
        for (i in 0 until words.size - 1) {
            val pair = "${words[i]} ${words[i + 1]}"
            if (similarity(pair, "hello buddy") >= threshold) {
                return true
            }
        }
        return false
    }

    /**
     * Strips leading "Hello Buddy" wake phrase (across EN/TE/HI/TA/KN/ML scripts & phonetic variants)
     * so inline commands like "Hello Buddy, open Chrome" execute immediately without a second prompt.
     * Preserves original casing/punctuation of the remaining command.
     */
    fun stripWakeWordPrefix(transcript: String, sensitivity: Float = 0.65f): String {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return ""

        // Strip optional conversational filler ("Hey ", "Ok ") immediately preceding wake phrase
        val withoutInterjection = trimmed.replace(Regex("^(?i)(?:hey|ok|okay|hi)[,\\s]+"), "").trim()
        val working = if (withoutInterjection.isNotEmpty()) withoutInterjection else trimmed

        val normalizedWorking = working.lowercase()
        for (variant in exactAndPhoneticVariants.sortedByDescending { it.length }) {
            if (normalizedWorking == variant) return ""
            if (normalizedWorking.startsWith(variant)) {
                val remainder = working.substring(variant.length)
                    .trimStart(',', ':', ';', '-', '–', '—', '.', '!', ' ', '\t')
                    .trim()
                return remainder
            }
        }

        // Fuzzy 2-word prefix match ("helo badi, open youtube")
        val tokens = working.split(Regex("\\s+"))
        if (tokens.size >= 2) {
            val clean0 = tokens[0].lowercase().replace(Regex("[^a-z\\u0900-\\u0D7F]"), "")
            val clean1 = tokens[1].lowercase().replace(Regex("[^a-z\\u0900-\\u0D7F]"), "")
            val pair = "$clean0 $clean1".trim()
            val threshold = when {
                sensitivity >= 0.8f -> 0.72f
                sensitivity >= 0.5f -> 0.78f
                else -> 0.86f
            }
            if (similarity(pair, "hello buddy") >= threshold) {
                if (tokens.size == 2) return ""
                return tokens.drop(2).joinToString(" ")
                    .trimStart(',', ':', ';', '-', '–', '—', '.', '!', ' ', '\t')
                    .trim()
            }
        }

        return trimmed
    }

    fun computeFrameRms(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until count) {
            val norm = samples[i] / 32768.0
            sumSq += norm * norm
        }
        return sqrt(sumSq / count).toFloat()
    }

    fun rmsThresholdForSensitivity(sensitivity: Float): Float {
        // Higher sensitivity => lower RMS threshold to trigger VAD gate
        val clamped = sensitivity.coerceIn(0.2f, 0.95f)
        return 0.09f - (clamped * 0.065f)
    }

    internal fun similarity(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        val dist = levenshtein(a, b)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshtein(lhs: String, rhs: String): Int {
        val dp = IntArray(rhs.length + 1) { it }
        for (i in 1..lhs.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..rhs.length) {
                val temp = dp[j]
                val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[rhs.length]
    }
}

/**
 * Optional custom LiteRT (.tflite) Keyword Spotting runner for "Hello Buddy".
 * Runs strictly on a single-thread executor per LiteRT CompiledModel mandate.
 */
class LiteRtKwsClassifier(private val context: Context) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val environment by lazy { Environment.create() }

    private var compiledModel: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()
    private var loadedPath: String? = null

    val isModelLoaded: Boolean
        get() = compiledModel != null

    suspend fun loadFromPath(path: String): Result<Unit> = withContext(dispatcher) {
        runCatching {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                throw IllegalStateException("KWS .tflite file not found at $path")
            }
            closeInternal()
            val model = CompiledModel.create(
                path,
                CompiledModel.Options(Accelerator.CPU),
                environment
            )
            val inBufs = model.createInputBuffers()
            val outBufs = model.createOutputBuffers()
            compiledModel = model
            inputs = inBufs
            outputs = outBufs
            loadedPath = path
        }.onFailure {
            closeInternal()
        }
    }

    suspend fun scoreAudioWindow(pcmWindow: FloatArray): Float? = withContext(dispatcher) {
        val model = compiledModel ?: return@withContext null
        if (inputs.isEmpty() || outputs.isEmpty()) return@withContext null
        return@withContext runCatching {
            // Simple spectral energy summary features mapped to input tensor length
            val features = extractCompactFeatures(pcmWindow)
            inputs[0].writeFloat(features)
            model.run(inputs, outputs)
            val scores = outputs[0].readFloat()
            scores.maxOrNull()
        }.getOrNull()
    }

    private fun extractCompactFeatures(pcm: FloatArray): FloatArray {
        val bins = 40 * 13 // 40 frames x 13 log-energy bands
        val out = FloatArray(bins)
        val hop = (pcm.size / 40).coerceAtLeast(1)
        for (frame in 0 until 40) {
            val start = frame * hop
            val end = minOf(start + hop, pcm.size)
            var energy = 1e-6f
            for (i in start until end) {
                energy += abs(pcm[i])
            }
            val logE = log10((energy / (end - start).coerceAtLeast(1)).toDouble()).toFloat()
            for (band in 0 until 13) {
                out[frame * 13 + band] = logE * (1f - band * 0.04f)
            }
        }
        return out
    }

    private fun closeInternal() {
        (inputs + outputs).forEach { runCatching { it.close() } }
        inputs = emptyList()
        outputs = emptyList()
        runCatching { compiledModel?.close() }
        compiledModel = null
        loadedPath = null
    }

    override fun close() {
        executor.execute { closeInternal() }
        executor.shutdown()
    }
}

object ActivationSoundPlayer {
    fun playChime() {
        runCatching {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            android.os.Handler(LooperHolder.main).postDelayed({
                runCatching { toneGen.release() }
            }, 250L)
        }
    }

    private object LooperHolder {
        val main = android.os.Looper.getMainLooper()
    }
}
