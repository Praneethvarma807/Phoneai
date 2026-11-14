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

class VoiceOutputManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var pulseJob: Job? = null

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

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                engine.language = Locale.getDefault()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        startSpeechPulse()
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        stopSpeechPulse()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        stopSpeechPulse()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _isSpeaking.value = false
                        stopSpeechPulse()
                    }
                })
                refreshVoices()
                _isReady.value = true
            }
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
        tts?.setSpeechRate(currentSpeed)
        if (voiceName.isNotBlank()) {
            val match = _offlineVoices.value.firstOrNull { it.name == voiceName }
            if (match != null) {
                tts?.voice = match
            }
        }
    }

    fun setResponseLanguage(language: AppLanguage) {
        val engine = tts ?: return
        if (!_isReady.value || currentVoiceName.isNotBlank()) return
        val locale = Locale.forLanguageTag(language.bcp47)
        val availability = runCatching { engine.isLanguageAvailable(locale) }
            .getOrDefault(TextToSpeech.LANG_MISSING_DATA)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            runCatching { engine.language = locale }
        } else {
            runCatching { engine.language = Locale.forLanguageTag("en-IN") }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        if (!_isReady.value || engine == null || text.isBlank()) {
            onDone?.invoke()
            return
        }
        engine.setSpeechRate(currentSpeed)
        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    _isSpeaking.value = true
                    startSpeechPulse()
                }

                override fun onDone(id: String?) {
                    _isSpeaking.value = false
                    stopSpeechPulse()
                    if (id == utteranceId) {
                        scope.launch { onDone() }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    _isSpeaking.value = false
                    stopSpeechPulse()
                    if (id == utteranceId) {
                        scope.launch { onDone() }
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    _isSpeaking.value = false
                    stopSpeechPulse()
                    if (id == utteranceId) {
                        scope.launch { onDone() }
                    }
                }
            })
        }
        val params = Bundle()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stop() {
        runCatching { tts?.stop() }
        _isSpeaking.value = false
        stopSpeechPulse()
    }

    private fun startSpeechPulse() {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            var phase = 0f
            while (_isSpeaking.value) {
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
        _speechLevel.value = 0f
    }

    fun shutdown() {
        stopSpeechPulse()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        scope.cancel()
    }
}
