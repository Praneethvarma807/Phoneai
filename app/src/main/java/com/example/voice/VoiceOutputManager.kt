package com.example.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.domain.model.AppLanguage
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VoiceOutputManager(
    context: Context,
    createTtsEngine: Boolean = true
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stateLock = Any()

    private var tts: TextToSpeech? = null
    private var pulseJob: Job? = null
    private var isShutdown = false

    private var activeUtteranceId: String? = null
    private var activeOnDone: (() -> Unit)? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speechLevel = MutableStateFlow(0f)
    val speechLevel: StateFlow<Float> = _speechLevel.asStateFlow()

    private val _offlineVoices = MutableStateFlow<List<Voice>>(emptyList())
    val offlineVoices: StateFlow<List<Voice>> = _offlineVoices.asStateFlow()

    private var currentSpeed: Float = 1.0f
    private var currentVoiceName: String = ""
    private var lastSpokenNormalized: String = ""
    private var lastSpokenAtMs: Long = 0L

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            onUtteranceStart(utteranceId)
        }

        override fun onDone(utteranceId: String?) {
            onUtteranceDone(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            onUtteranceStop(utteranceId, interrupted)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onUtteranceError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onUtteranceError(utteranceId)
        }
    }

    init {
        if (createTtsEngine) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS && !isShutdown) {
                    val engine = tts ?: return@TextToSpeech
                    runCatching { engine.language = Locale.getDefault() }
                    engine.setOnUtteranceProgressListener(progressListener)
                    refreshVoices()
                    _isReady.value = true
                    configure(currentSpeed, currentVoiceName)
                } else {
                    _isReady.value = false
                }
            }
        }
    }

    internal fun attachTtsForTesting(engine: TextToSpeech?, ready: Boolean = true) {
        synchronized(stateLock) {
            tts = engine
            engine?.setOnUtteranceProgressListener(progressListener)
            _isReady.value = ready
        }
    }

    fun refreshVoices() {
        val allVoices = runCatching { tts?.voices?.toList().orEmpty() }.getOrElse { emptyList() }
        val offline = allVoices
            .filter { !it.isNetworkConnectionRequired }
            .sortedBy { it.locale.displayName }
        _offlineVoices.value = offline.ifEmpty { allVoices.take(20) }
    }

    fun configure(speed: Float, voiceName: String) {
        currentSpeed = speed.coerceIn(0.6f, 1.8f)
        currentVoiceName = voiceName
        val engine = tts ?: return
        runCatching { engine.setSpeechRate(currentSpeed) }
        if (voiceName.isNotBlank()) {
            val match = _offlineVoices.value.firstOrNull { it.name == voiceName }
                ?: runCatching { engine.voices?.firstOrNull { it.name == voiceName } }.getOrNull()
            if (match != null) {
                runCatching { engine.voice = match }
            }
        }
    }

    fun setResponseLanguage(language: AppLanguage): Boolean {
        val engine = tts ?: return false
        if (!_isReady.value) return false

        val primaryLocale = Locale.forLanguageTag(language.bcp47)
        if (currentVoiceName.isNotBlank()) {
            val savedVoice = _offlineVoices.value.firstOrNull { it.name == currentVoiceName }
                ?: runCatching { engine.voices?.firstOrNull { it.name == currentVoiceName } }.getOrNull()
            if (savedVoice != null && savedVoice.locale.language == primaryLocale.language) {
                runCatching { engine.voice = savedVoice }
                return true
            }
        }

        val candidates = listOf(
            primaryLocale,
            Locale.forLanguageTag(primaryLocale.language),
            Locale.forLanguageTag("en-IN"),
            Locale.US,
            Locale.getDefault()
        ).distinct()

        for (candidate in candidates) {
            val availability = runCatching { engine.isLanguageAvailable(candidate) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                val applied = runCatching {
                    engine.language = candidate
                    true
                }.getOrDefault(false)
                if (applied) return candidate.language == primaryLocale.language
            }
        }
        return false
    }

    internal fun sanitizeForSpeech(rawText: String): String {
        val cleaned = rawText
            .replace(Regex("\\s*\\((?:Error\\s+\\d+|code\\s+\\d+|hardwareKeystore=[^)]+)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(?:java|android|kotlin)\\.[a-zA-Z0-9_.]+Exception:?\\s*"), "")
            .replace(Regex("https?://\\S+"), "link")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length <= 280) return cleaned
        val cut = cleaned.lastIndexOf('.', startIndex = 280)
        return if (cut >= 120) cleaned.substring(0, cut + 1) else cleaned.take(277) + "..."
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val spokenText = sanitizeForSpeech(text)
        val engine = tts
        if (isShutdown || !_isReady.value || engine == null || spokenText.isBlank()) {
            onDone?.invoke()
            return
        }

        val now = System.currentTimeMillis()
        val normalized = spokenText.lowercase()
        val isImmediateDuplicate = synchronized(stateLock) {
            val dup = normalized == lastSpokenNormalized &&
                (now - lastSpokenAtMs) < 1_500L &&
                activeUtteranceId != null
            if (!dup) {
                lastSpokenNormalized = normalized
                lastSpokenAtMs = now
            }
            dup
        }
        if (isImmediateDuplicate) {
            onDone?.invoke()
            return
        }

        val previousCallback: (() -> Unit)?
        val utteranceId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            previousCallback = activeOnDone
            activeUtteranceId = utteranceId
            activeOnDone = onDone
        }
        // Complete any superseded utterance callback cleanly so callers never hang
        if (previousCallback != null) {
            dispatchCallback(previousCallback)
        }

        runCatching { engine.stop() }
        runCatching { engine.setSpeechRate(currentSpeed) }
        engine.setOnUtteranceProgressListener(progressListener)

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val queueResult = runCatching {
            engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)

        if (queueResult == TextToSpeech.ERROR) {
            completeUtterance(utteranceId, invokeCallback = true)
        }
    }

    fun stop() {
        val callback = synchronized(stateLock) {
            val cb = activeOnDone
            activeUtteranceId = null
            activeOnDone = null
            cb
        }
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        WakeWordForegroundService.setTtsSpeaking(false)
        stopSpeechPulse()
        if (callback != null) {
            dispatchCallback(callback)
        }
    }

    internal fun onUtteranceStart(utteranceId: String?) {
        val matchesCurrent = synchronized(stateLock) {
            activeUtteranceId == null || utteranceId == null || utteranceId == activeUtteranceId
        }
        if (!matchesCurrent) return
        _isSpeaking.value = true
        WakeWordForegroundService.setTtsSpeaking(true)
        startSpeechPulse()
    }

    internal fun onUtteranceDone(utteranceId: String?) {
        completeUtterance(utteranceId, invokeCallback = true)
    }

    internal fun onUtteranceStop(utteranceId: String?, interrupted: Boolean) {
        // Always finish stale/current tracking cleanly when interrupted or stopped
        completeUtterance(utteranceId, invokeCallback = true)
    }

    internal fun onUtteranceError(utteranceId: String?) {
        completeUtterance(utteranceId, invokeCallback = true)
    }

    private fun completeUtterance(utteranceId: String?, invokeCallback: Boolean) {
        val callbackToRun: (() -> Unit)?
        synchronized(stateLock) {
            val currentId = activeUtteranceId
            if (currentId != null && utteranceId != null && utteranceId != currentId) {
                // Stale callback from an older superseded utterance: ignore so we don't clobber active speech
                return
            }
            callbackToRun = if (invokeCallback) activeOnDone else null
            activeUtteranceId = null
            activeOnDone = null
        }
        _isSpeaking.value = false
        WakeWordForegroundService.setTtsSpeaking(false)
        stopSpeechPulse()
        if (callbackToRun != null) {
            dispatchCallback(callbackToRun)
        }
    }

    private fun dispatchCallback(callback: () -> Unit) {
        if (isShutdown) {
            runCatching { callback() }
            return
        }
        scope.launch {
            runCatching { callback() }
        }
    }

    private fun startSpeechPulse() {
        if (isShutdown) return
        pulseJob?.cancel()
        pulseJob = scope.launch {
            var phase = 0f
            while (_isSpeaking.value && !isShutdown) {
                phase += 0.45f
                val level = 0.35f + 0.55f * kotlin.math.abs(kotlin.math.sin(phase))
                _speechLevel.value = level
                delay(60L)
            }
            _speechLevel.value = 0f
        }
    }

    private fun stopSpeechPulse() {
        pulseJob?.cancel()
        pulseJob = null
        _speechLevel.value = 0f
    }

    fun shutdown() {
        isShutdown = true
        val callback = synchronized(stateLock) {
            val cb = activeOnDone
            activeUtteranceId = null
            activeOnDone = null
            cb
        }
        stopSpeechPulse()
        _isSpeaking.value = false
        WakeWordForegroundService.setTtsSpeaking(false)
        _isReady.value = false
        runCatching { tts?.setOnUtteranceProgressListener(null) }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        if (callback != null) {
            runCatching { callback() }
        }
        scope.cancel()
    }
}
