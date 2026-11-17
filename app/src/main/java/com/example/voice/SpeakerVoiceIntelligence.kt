package com.example.voice

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class HardwareAudioEffectsStatus(
    val noiseSuppressorAttached: Boolean = false,
    val acousticEchoCancelerAttached: Boolean = false,
    val automaticGainControlAttached: Boolean = false
) {
    fun summaryLabel(): String = buildList {
        if (noiseSuppressorAttached) add("NS")
        if (acousticEchoCancelerAttached) add("AEC")
        if (automaticGainControlAttached) add("AGC")
    }.ifEmpty { listOf("DSP") }.joinToString("+")
}

/**
 * Safely attaches Android hardware NoiseSuppressor, AcousticEchoCanceler, and
 * AutomaticGainControl to an AudioRecord session ID and cleans them up on release.
 */
class AudioEffectController : AutoCloseable {
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    fun attachToSession(audioSessionId: Int): HardwareAudioEffectsStatus {
        close()
        if (audioSessionId <= 0) return HardwareAudioEffectsStatus()

        val nsOk = runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }?.also { noiseSuppressor = it } != null
            } else false
        }.getOrDefault(false)

        val aecOk = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }?.also { echoCanceler = it } != null
            } else false
        }.getOrDefault(false)

        val agcOk = runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }?.also { gainControl = it } != null
            } else false
        }.getOrDefault(false)

        return HardwareAudioEffectsStatus(
            noiseSuppressorAttached = nsOk,
            acousticEchoCancelerAttached = aecOk,
            automaticGainControlAttached = agcOk
        )
    }

    override fun close() {
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { gainControl?.release() }
        noiseSuppressor = null
        echoCanceler = null
        gainControl = null
    }
}

/**
 * Software DSP preprocessing stage mirroring WebRTC Audio Processing essentials:
 * 1. DC offset removal (eliminates mic bias & ultra-low rumble)
 * 2. Pre-emphasis filter (x[n] - 0.95 * x[n-1]) to clarify consonant & formant structure
 * 3. Adaptive stationary noise-floor estimation & soft noise gating (fan/AC/traffic suppression)
 * 4. Soft RMS Automatic Gain Control (AGC) + smooth tanh limiter to avoid clipping
 */
object AudioSignalProcessor {

    data class NoiseFloorTracker(
        var estimatedFloorRms: Float = 0.015f,
        private var initialized: Boolean = false
    ) {
        fun updateAndGetFloor(frameRms: Float): Float {
            if (!initialized) {
                estimatedFloorRms = frameRms.coerceIn(0.004f, 0.08f)
                initialized = true
                return estimatedFloorRms
            }
            // Fast decay down toward quiet floor, slow upward drift so speech doesn't corrupt noise estimate
            val alpha = if (frameRms < estimatedFloorRms) 0.25f else 0.025f
            estimatedFloorRms = ((1f - alpha) * estimatedFloorRms + alpha * frameRms)
                .coerceIn(0.003f, 0.12f)
            return estimatedFloorRms
        }
    }

    fun pcm16ToFloat(samples: ShortArray, count: Int = samples.size): FloatArray {
        val len = count.coerceIn(0, samples.size)
        val out = FloatArray(len)
        for (i in 0 until len) {
            out[i] = samples[i] / 32768f
        }
        return out
    }

    fun preprocessFrame(
        samples: ShortArray,
        count: Int = samples.size,
        noiseFloorTracker: NoiseFloorTracker = NoiseFloorTracker(),
        targetRms: Float = 0.16f
    ): FloatArray = preprocessFloat(
        pcm = pcm16ToFloat(samples, count),
        noiseFloorTracker = noiseFloorTracker,
        targetRms = targetRms
    )

    fun preprocessFloat(
        pcm: FloatArray,
        noiseFloorTracker: NoiseFloorTracker = NoiseFloorTracker(),
        targetRms: Float = 0.16f
    ): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size

        // 1. DC Offset Removal
        var sum = 0.0
        for (i in 0 until n) sum += pcm[i]
        val mean = (sum / n).toFloat()

        // 2. Pre-emphasis (high-pass consonant articulation boost)
        val emphasized = FloatArray(n)
        var prev = pcm[0] - mean
        emphasized[0] = prev
        for (i in 1 until n) {
            val centered = pcm[i] - mean
            emphasized[i] = centered - 0.95f * prev
            prev = centered
        }

        // 3. Adaptive stationary noise suppression (smooth soft gate based on noise floor)
        val rawRms = computeRms(emphasized)
        val floor = noiseFloorTracker.updateAndGetFloor(rawRms)
        val gateThreshold = floor * 1.45f

        val denoised = FloatArray(n)
        for (i in 0 until n) {
            val v = emphasized[i]
            val mag = abs(v)
            denoised[i] = if (mag <= floor * 0.8f) {
                v * 0.15f // heavily attenuate stationary noise floor
            } else if (mag < gateThreshold) {
                val ratio = (mag - floor * 0.8f) / (gateThreshold - floor * 0.8f).coerceAtLeast(1e-5f)
                v * (0.15f + 0.85f * ratio)
            } else {
                v
            }
        }

        // 4. Soft RMS Automatic Gain Control (only boost true above-floor speech; don't amplify pure silence/fan)
        val cleanRms = computeRms(denoised)
        val snrRatio = cleanRms / floor.coerceAtLeast(0.003f)
        val gain = if (snrRatio > 1.8f && cleanRms > 0.012f) {
            (targetRms / cleanRms).coerceIn(0.6f, 4.5f)
        } else {
            1.0f
        }

        for (i in 0 until n) {
            val scaled = denoised[i] * gain
            denoised[i] = if (abs(scaled) > 0.85f) tanh(scaled.toDouble()).toFloat() else scaled
        }
        return denoised
    }

    fun computeRms(samples: FloatArray, start: Int = 0, end: Int = samples.size): Float {
        val s = start.coerceAtLeast(0)
        val e = end.coerceAtMost(samples.size)
        if (e <= s) return 0f
        var sumSq = 0.0
        for (i in s until e) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / (e - s)).toFloat()
    }
}

data class VadDecision(
    val isSpeech: Boolean,
    val speechProbability: Float,
    val rms: Float,
    val zcr: Float,
    val speechBandRatio: Float,
    val snrDb: Float
)

/**
 * Dual-feature Voice Activity Detector combining:
 * - SNR over adaptive stationary noise floor (rejects fan/AC/traffic baseline)
 * - Zero-Crossing Rate in articulated speech bounds [0.015 .. 0.42]
 * - 300-3400 Hz telephony/human-voice spectral energy concentration ratio (Goertzel DFT probes)
 */
object DualFeatureVad {
    private val speechProbeFreqs = doubleArrayOf(240.0, 420.0, 680.0, 1050.0, 1550.0, 2200.0, 2950.0)
    private val nonSpeechProbeFreqs = doubleArrayOf(60.0, 110.0, 4600.0, 6200.0)

    fun evaluateFrame(
        frame: FloatArray,
        sampleRate: Int = 16_000,
        noiseFloorRms: Float = 0.015f,
        sensitivity: Float = 0.65f
    ): VadDecision {
        if (frame.size < 64) {
            return VadDecision(false, 0f, 0f, 0f, 0f, -20f)
        }
        val rms = AudioSignalProcessor.computeRms(frame)
        val zcr = computeZcr(frame)

        val speechEnergy = speechProbeFreqs.sumOf { goertzelPower(frame, it, sampleRate) } / speechProbeFreqs.size
        val outEnergy = nonSpeechProbeFreqs.sumOf { goertzelPower(frame, it, sampleRate) } / nonSpeechProbeFreqs.size
        val total = (speechEnergy + outEnergy).coerceAtLeast(1e-9)
        val speechBandRatio = (speechEnergy / total).toFloat().coerceIn(0f, 1f)

        val effectiveFloor = noiseFloorRms.coerceAtLeast(0.006f)
        val snrLinear = rms / effectiveFloor
        val snrDb = (20.0 * log10(snrLinear.toDouble().coerceAtLeast(0.05))).toFloat()

        // Score components into calibrated 0..1 probability
        val energyScore = ((rms - effectiveFloor * 1.35f) / (effectiveFloor * 2.5f)).coerceIn(0f, 1f)
        val zcrScore = if (zcr in 0.015f..0.42f) 1f else if (zcr in 0.008f..0.55f) 0.45f else 0f
        val spectralScore = ((speechBandRatio - 0.38f) / 0.35f).coerceIn(0f, 1f)

        val prob = (0.45f * energyScore + 0.35f * spectralScore + 0.20f * zcrScore).coerceIn(0f, 1f)
        val probThreshold = (0.68f - sensitivity.coerceIn(0.2f, 0.95f) * 0.28f).coerceIn(0.32f, 0.65f)
        val isSpeech = rms >= 0.014f && prob >= probThreshold && speechBandRatio >= 0.42f

        return VadDecision(
            isSpeech = isSpeech,
            speechProbability = prob,
            rms = rms,
            zcr = zcr,
            speechBandRatio = speechBandRatio,
            snrDb = snrDb
        )
    }

    internal fun computeZcr(frame: FloatArray): Float {
        if (frame.size < 2) return 0f
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i - 1] >= 0f && frame[i] < 0f) || (frame[i - 1] < 0f && frame[i] >= 0f)) {
                crossings++
            }
        }
        return crossings.toFloat() / (frame.size - 1)
    }

    internal fun goertzelPower(samples: FloatArray, targetFreqHz: Double, sampleRate: Int): Double {
        if (samples.isEmpty() || targetFreqHz >= sampleRate / 2.0) return 0.0
        val k = (0.5 + (samples.size * targetFreqHz) / sampleRate).toInt().coerceAtLeast(1)
        val omega = (2.0 * PI * k) / samples.size
        val coeff = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        for (i in samples.indices) {
            // Apply subtle Hann taper to prevent spectral leakage
            val win = 0.5 * (1.0 - cos((2.0 * PI * i) / (samples.size - 1).coerceAtLeast(1)))
            q0 = coeff * q1 - q2 + samples[i] * win
            q2 = q1
            q1 = q0
        }
        val power = q1 * q1 + q2 * q2 - q1 * q2 * coeff
        return (power / (samples.size * samples.size)).coerceAtLeast(0.0)
    }
}

@Serializable
data class PrimarySpeakerProfile(
    val embedding: List<Float>,
    val pitchHzMean: Float,
    val sampleCount: Int,
    val createdAtMs: Long
) {
    fun toJson(): String = ProfileJson.json.encodeToString(this)

    companion object {
        fun fromJson(raw: String): PrimarySpeakerProfile? {
            if (raw.isBlank()) return null
            return runCatching { ProfileJson.json.decodeFromString<PrimarySpeakerProfile>(raw) }.getOrNull()
        }
    }
}

private object ProfileJson {
    val json = Json { ignoreUnknownKeys = true }
}

data class SpeakerVerificationResult(
    val accepted: Boolean,
    val similarityScore: Float,
    val vadSpeechRatio: Float,
    val reason: String,
    val mode: String
)

/**
 * Extracts compact 24-dimensional L2-normalized vocal embeddings capturing:
 * - 16 log-spectral vocal tract formant filterbank bands (120 Hz .. 4000 Hz)
 * - Spectral centroid, rolloff, low/high tilt
 * - Fundamental F0 pitch & harmonic periodicity via normalized autocorrelation
 * - Temporal formant flux & zero-crossing dynamics
 */
object SpeakerEmbeddingExtractor {
    const val EMBEDDING_DIM = 24
    private val bankFreqs = doubleArrayOf(
        130.0, 200.0, 290.0, 390.0, 510.0, 650.0, 820.0, 1020.0,
        1250.0, 1520.0, 1830.0, 2180.0, 2570.0, 3000.0, 3480.0, 3980.0
    )

    data class ExtractedEmbedding(
        val vector: FloatArray,
        val meanPitchHz: Float,
        val voicedRatio: Float
    )

    fun extract(pcm: FloatArray, sampleRate: Int = 16_000): ExtractedEmbedding? {
        if (pcm.size < 400) return null
        val frameSize = (sampleRate / 40).coerceAtLeast(256) // 25ms
        val hop = (sampleRate / 80).coerceAtLeast(128) // 12.5ms
        val tracker = AudioSignalProcessor.NoiseFloorTracker()
        val acc = DoubleArray(EMBEDDING_DIM)
        var voicedFrames = 0
        var totalFrames = 0
        var pitchSum = 0.0
        var prevFrameSpectrum: DoubleArray? = null
        var fluxSum = 0.0

        var start = 0
        while (start + frameSize <= pcm.size) {
            val slice = pcm.copyOfRange(start, start + frameSize)
            val frameRms = AudioSignalProcessor.computeRms(slice)
            val floor = tracker.updateAndGetFloor(frameRms)
            val vad = DualFeatureVad.evaluateFrame(slice, sampleRate, floor, sensitivity = 0.72f)
            totalFrames++

            if (vad.isSpeech || vad.rms >= 0.015f) {
                voicedFrames++
                val bandLog = DoubleArray(16)
                var totalPower = 1e-12
                var weightedFreqSum = 0.0
                for (b in 0 until 16) {
                    val p = DualFeatureVad.goertzelPower(slice, bankFreqs[b], sampleRate)
                    totalPower += p
                    weightedFreqSum += p * bankFreqs[b]
                    bandLog[b] = ln(p + 1e-8)
                }

                // Cepstral mean subtraction across bands normalizes recording loudness differences
                val bandMean = bandLog.average()
                for (b in 0 until 16) {
                    acc[b] += (bandLog[b] - bandMean)
                }

                // Spectral centroid normalized (0..1 -> scaled)
                val centroidNorm = (weightedFreqSum / totalPower) / 4000.0
                acc[16] += centroidNorm * 2.5

                // Spectral rolloff (85% cumulative energy)
                var cum = 0.0
                var rolloffIdx = 15
                val targetCum = totalPower * 0.85
                for (b in 0 until 16) {
                    cum += DualFeatureVad.goertzelPower(slice, bankFreqs[b], sampleRate)
                    if (cum >= targetCum) {
                        rolloffIdx = b
                        break
                    }
                }
                acc[17] += (rolloffIdx / 15.0) * 2.5

                // Low-to-high vocal spectral tilt
                val lowSum = bandLog.slice(0..5).average()
                val highSum = bandLog.slice(10..15).average()
                acc[18] += (lowSum - highSum).coerceIn(-6.0, 6.0) * 0.4

                // ZCR normalized
                acc[19] += (vad.zcr * 5.0).toDouble()

                // Fundamental F0 & Harmonicity via normalized autocorrelation (80..360 Hz)
                val (pitchHz, harmonicity) = estimatePitchAndHarmonicity(slice, sampleRate)
                if (pitchHz > 0f) {
                    pitchSum += pitchHz
                    acc[20] += ((pitchHz - 80f) / 280f) * 3.0
                }
                acc[21] += harmonicity * 2.5

                // Formant spectral flux relative to previous frame
                val prev = prevFrameSpectrum
                if (prev != null) {
                    var diffSq = 0.0
                    for (b in 0 until 16) {
                        val d = (bandLog[b] - bandMean) - prev[b]
                        diffSq += d * d
                    }
                    fluxSum += sqrt(diffSq / 16.0)
                }
                prevFrameSpectrum = DoubleArray(16) { b -> bandLog[b] - bandMean }
            }
            start += hop
        }

        if (voicedFrames == 0) return null
        val voicedRatio = voicedFrames.toFloat() / totalFrames.coerceAtLeast(1)
        acc[22] = (fluxSum / voicedFrames) * 1.4
        acc[23] = voicedRatio * 1.5

        val vec = FloatArray(EMBEDDING_DIM) { i -> (acc[i] / voicedFrames).toFloat() }
        // Re-apply frame-count invariant indices for 22 and 23
        vec[22] = acc[22].toFloat()
        vec[23] = acc[23].toFloat()

        val norm = normalizeL2(vec)
        val meanPitch = if (pitchSum > 0.0) (pitchSum / voicedFrames).toFloat() else 0f
        return ExtractedEmbedding(norm, meanPitch, voicedRatio)
    }

    internal fun estimatePitchAndHarmonicity(frame: FloatArray, sampleRate: Int): Pair<Float, Double> {
        val minLag = (sampleRate / 360).coerceAtLeast(20)
        val maxLag = (sampleRate / 75).coerceAtMost(frame.size / 2)
        if (maxLag <= minLag) return 0f to 0.0

        var zeroLagEnergy = 0.0
        for (i in frame.indices) zeroLagEnergy += frame[i] * frame[i]
        if (zeroLagEnergy < 1e-7) return 0f to 0.0

        var bestCorr = -1.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var num = 0.0
            var e1 = 0.0
            var e2 = 0.0
            val limit = frame.size - lag
            for (i in 0 until limit) {
                val a = frame[i].toDouble()
                val b = frame[i + lag].toDouble()
                num += a * b
                e1 += a * a
                e2 += b * b
            }
            val denom = sqrt(e1 * e2).coerceAtLeast(1e-9)
            val corr = num / denom
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        return if (bestCorr >= 0.32 && bestLag > 0) {
            (sampleRate.toFloat() / bestLag.toFloat()) to bestCorr.coerceIn(0.0, 1.0)
        } else {
            0f to bestCorr.coerceIn(0.0, 1.0)
        }
    }

    internal fun normalizeL2(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val mag = sqrt(sumSq).toFloat()
        if (mag < 1e-6f) return vec.copyOf()
        return FloatArray(vec.size) { i -> vec[i] / mag }
    }

    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na * nb).toFloat()
        return if (denom < 1e-6f) 0f else (dot.toFloat() / denom).coerceIn(-1f, 1f)
    }
}

object PrimarySpeakerVerifier {

    fun enrollFromSamples(
        samples: List<FloatArray>,
        sampleRate: Int = 16_000,
        nowMs: Long = System.currentTimeMillis()
    ): Result<PrimarySpeakerProfile> {
        if (samples.isEmpty()) {
            return Result.failure(IllegalArgumentException("Provide at least one voice sample to enroll."))
        }
        val centroid = FloatArray(SpeakerEmbeddingExtractor.EMBEDDING_DIM)
        var validSamples = 0
        var pitchAccum = 0f
        var pitchCount = 0

        for (rawSample in samples) {
            val clean = AudioSignalProcessor.preprocessFloat(rawSample)
            val extracted = SpeakerEmbeddingExtractor.extract(clean, sampleRate) ?: continue
            if (extracted.voicedRatio < 0.10f) continue
            for (i in centroid.indices) {
                centroid[i] += extracted.vector[i]
            }
            if (extracted.meanPitchHz > 0f) {
                pitchAccum += extracted.meanPitchHz
                pitchCount++
            }
            validSamples++
        }

        if (validSamples == 0) {
            return Result.failure(
                IllegalStateException("No clear speech detected in enrollment audio. Please speak clearly close to the microphone.")
            )
        }

        val normalized = SpeakerEmbeddingExtractor.normalizeL2(centroid)
        val avgPitch = if (pitchCount > 0) pitchAccum / pitchCount else 150f
        return Result.success(
            PrimarySpeakerProfile(
                embedding = normalized.toList(),
                pitchHzMean = avgPitch,
                sampleCount = validSamples,
                createdAtMs = nowMs
            )
        )
    }

    fun verify(
        rawPcm: FloatArray,
        profile: PrimarySpeakerProfile?,
        threshold: Float = 0.72f,
        enforcePrimarySpeaker: Boolean = false,
        sampleRate: Int = 16_000
    ): SpeakerVerificationResult {
        val clean = AudioSignalProcessor.preprocessFloat(rawPcm)
        val extracted = SpeakerEmbeddingExtractor.extract(clean, sampleRate)
            ?: return SpeakerVerificationResult(
                accepted = false,
                similarityScore = 0f,
                vadSpeechRatio = 0f,
                reason = "Rejected non-speech or silent audio.",
                mode = if (enforcePrimarySpeaker) "SPEAKER_LOCKED" else "OPEN_VAD"
            )

        if (extracted.voicedRatio < 0.12f) {
            return SpeakerVerificationResult(
                accepted = false,
                similarityScore = 0f,
                vadSpeechRatio = extracted.voicedRatio,
                reason = "Rejected background noise (voiced=${"%.0f".format(extracted.voicedRatio * 100)}%).",
                mode = if (enforcePrimarySpeaker) "SPEAKER_LOCKED" else "OPEN_VAD"
            )
        }

        if (!enforcePrimarySpeaker || profile == null || profile.embedding.size != SpeakerEmbeddingExtractor.EMBEDDING_DIM) {
            return SpeakerVerificationResult(
                accepted = true,
                similarityScore = 1.0f,
                vadSpeechRatio = extracted.voicedRatio,
                reason = "Speech detected (open speaker mode).",
                mode = "OPEN_VAD"
            )
        }

        val profileVec = profile.embedding.toFloatArray()
        val cosine = SpeakerEmbeddingExtractor.cosineSimilarity(extracted.vector, profileVec)

        // Blend vocal tract spectral similarity with fundamental F0 pitch consistency
        val pitchPenalty = if (profile.pitchHzMean > 0f && extracted.meanPitchHz > 0f) {
            val relDiff = abs(extracted.meanPitchHz - profile.pitchHzMean) / profile.pitchHzMean.coerceAtLeast(60f)
            (relDiff * 0.22f).coerceIn(0f, 0.28f)
        } else 0f

        val combinedScore = (cosine - pitchPenalty).coerceIn(0f, 1f)
        val accepted = combinedScore >= threshold
        val reason = if (accepted) {
            "Verified primary speaker (match=${"%.0f".format(combinedScore * 100)}%)."
        } else {
            "Rejected background/other speaker (match=${"%.0f".format(combinedScore * 100)}% < ${"%.0f".format(threshold * 100)}%)."
        }
        return SpeakerVerificationResult(
            accepted = accepted,
            similarityScore = combinedScore,
            vadSpeechRatio = extracted.voicedRatio,
            reason = reason,
            mode = "SPEAKER_LOCKED"
        )
    }
}
