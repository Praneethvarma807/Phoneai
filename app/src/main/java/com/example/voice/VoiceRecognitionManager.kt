package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.domain.model.SpeechLanguageOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceListeningState(
    val isListening: Boolean = false,
    val rmsNormalized: Float = 0f,
    val partialTranscript: String = "",
    val durationMs: Long = 0L,
    val isRecognitionAvailable: Boolean = true,
    val isOfflineRecognitionSupported: Boolean = false,
    val missingPackWarning: String? = null,
    val error: String? = null
)

class VoiceRecognitionManager(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var timerJob: Job? = null
    private var startedAtMs: Long = 0L

    private val _state = MutableStateFlow(checkCapabilities())
    val state: StateFlow<VoiceListeningState> = _state.asStateFlow()

    fun checkCapabilities(): VoiceListeningState {
        val available = SpeechRecognizer.isRecognitionAvailable(appContext)
        val offlineSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
        } else {
            false
        }
        return VoiceListeningState(
            isRecognitionAvailable = available,
            isOfflineRecognitionSupported = offlineSupported,
            missingPackWarning = if (!offlineSupported) {
                "On-device offline speech pack was not detected by the OS; install offline voice packs in Language Settings or use text input."
            } else {
                null
            }
        )
    }

    fun startListening(
        language: SpeechLanguageOption,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            val caps = checkCapabilities()
            if (!caps.isRecognitionAvailable) {
                val msg = "No Speech Recognition service available on this device. Please use text input."
                _state.update { it.copy(isListening = false, isRecognitionAvailable = false, error = msg) }
                onError(msg)
                return@post
            }

            destroyRecognizerInternal()

            val sr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }
            recognizer = sr

            val localeTag = when (language) {
                SpeechLanguageOption.AUTO -> "en-IN"
                SpeechLanguageOption.EN_IN -> "en-IN"
                SpeechLanguageOption.TE_IN -> "te-IN"
                SpeechLanguageOption.HI_IN -> "hi-IN"
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    startedAtMs = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            isListening = true,
                            partialTranscript = "",
                            durationMs = 0L,
                            error = null
                        )
                    }
                    startDurationTimer()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _state.update { it.copy(rmsNormalized = norm) }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    stopDurationTimer()
                    _state.update { it.copy(isListening = false, rmsNormalized = 0f) }
                }

                override fun onError(error: Int) {
                    stopDurationTimer()
                    val msg = mapSpeechError(error, language)
                    _state.update {
                        it.copy(
                            isListening = false,
                            rmsNormalized = 0f,
                            error = msg
                        )
                    }
                    onError(msg)
                }

                override fun onResults(results: Bundle?) {
                    stopDurationTimer()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val transcript = matches.firstOrNull()?.trim().orEmpty()
                    _state.update {
                        it.copy(
                            isListening = false,
                            rmsNormalized = 0f,
                            partialTranscript = transcript,
                            error = null
                        )
                    }
                    if (transcript.isNotBlank()) {
                        onResult(transcript)
                    } else {
                        onError("No speech detected. Try again or type your command.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        _state.update { it.copy(partialTranscript = partial) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            sr.startListening(intent)
        }
    }

    fun stopListening() {
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
            stopDurationTimer()
            _state.update { it.copy(isListening = false, rmsNormalized = 0f) }
        }
    }

    fun cancelListening() {
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            destroyRecognizerInternal()
            stopDurationTimer()
            _state.update { it.copy(isListening = false, rmsNormalized = 0f, partialTranscript = "") }
        }
    }

    private fun destroyRecognizerInternal() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_state.value.isListening) {
                _state.update { it.copy(durationMs = System.currentTimeMillis() - startedAtMs) }
                delay(100L)
            }
        }
    }

    private fun stopDurationTimer() {
        timerJob?.cancel()
    }

    private fun mapSpeechError(code: Int, language: SpeechLanguageOption): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Offline speech model for ${language.displayName} is not installed on this device. Install the offline language pack or use text input."
        SpeechRecognizer.ERROR_NO_MATCH -> "Could not recognize speech. Please speak clearly or type."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy; please retry."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected before timeout."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "${language.displayName} offline speech pack is missing. Download it in Android Voice Settings or use text input."
        else -> "Speech recognition error (code $code)."
    }

    fun shutdown() {
        cancelListening()
    }
}
