package com.example.voice

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.CommanderApplication
import com.example.MainActivity
import com.example.R
import com.example.data.preferences.CommanderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface WakeWordEvent {
    data class Detected(val transcript: String, val confidenceNote: String) : WakeWordEvent
    data class Error(val message: String) : WakeWordEvent
}

internal sealed interface SpeechVerificationOutcome {
    data class Matched(val transcript: String) : SpeechVerificationOutcome
    data object NoMatch : SpeechVerificationOutcome
    data class ServiceFailure(val errorCode: Int?, val message: String) : SpeechVerificationOutcome
    data object Skipped : SpeechVerificationOutcome
}

internal sealed interface VerifierStepDecision {
    data class TriggerWake(val transcript: String) : VerifierStepDecision
    data object ContinueCooldown : VerifierStepDecision
    data class SuspendedBackoff(val reason: String, val pauseMs: Long) : VerifierStepDecision
    data class FatalPermissionStop(val reason: String) : VerifierStepDecision
}

/**
 * Pure Kotlin state machine governing wake-word cooldowns, Android 11+ background SpeechRecognizer
 * eligibility, exponential error backoff, and AudioRecord retry limits across loop restarts.
 */
internal class WakeWordLoopController(
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {
    var cooldownUntilMs: Long = 0L
        private set
    var consecutiveSpeechErrors: Int = 0
        private set
    var speechVerifierSuspendedUntilMs: Long = 0L
        private set
    var consecutiveAudioInitFailures: Int = 0
        private set

    fun isInCooldown(nowMs: Long): Boolean = nowMs < cooldownUntilMs

    fun recordKwsDetection(nowMs: Long, cooldownMs: Long = WAKE_COOLDOWN_MS) {
        cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + cooldownMs)
        consecutiveSpeechErrors = 0
    }

    fun canUseSpeechVerifier(nowMs: Long, isAppInForeground: Boolean): Boolean {
        if (nowMs < cooldownUntilMs) return false
        if (nowMs < speechVerifierSuspendedUntilMs) return false
        // Android 11+ restricts microphone access for background SpeechRecognizer IPC calls
        if (sdkIntProvider() >= Build.VERSION_CODES.R && !isAppInForeground) return false
        return true
    }

    fun recordAudioInitSuccess() {
        consecutiveAudioInitFailures = 0
    }

    /** Returns true if the loop should abort because mic initialization keeps failing. */
    fun recordAudioInitFailure(): Boolean {
        consecutiveAudioInitFailures++
        return consecutiveAudioInitFailures >= MAX_AUDIO_INIT_FAILURES
    }

    fun handleSpeechOutcome(nowMs: Long, outcome: SpeechVerificationOutcome): VerifierStepDecision {
        return when (outcome) {
            is SpeechVerificationOutcome.Matched -> {
                consecutiveSpeechErrors = 0
                speechVerifierSuspendedUntilMs = 0L
                cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + WAKE_COOLDOWN_MS)
                VerifierStepDecision.TriggerWake(outcome.transcript)
            }
            SpeechVerificationOutcome.NoMatch, SpeechVerificationOutcome.Skipped -> {
                consecutiveSpeechErrors = 0
                cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + WAKE_COOLDOWN_MS)
                VerifierStepDecision.ContinueCooldown
            }
            is SpeechVerificationOutcome.ServiceFailure -> {
                if (outcome.errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    return VerifierStepDecision.FatalPermissionStop(
                        "Microphone permission denied for wake word verification."
                    )
                }
                consecutiveSpeechErrors++
                val shift = (consecutiveSpeechErrors - 1).coerceIn(0, 4)
                val backoffMs = minOf(MAX_BACKOFF_MS, BASE_ERROR_BACKOFF_MS * (1L shl shift))
                cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + backoffMs)
                if (consecutiveSpeechErrors >= MAX_CONSECUTIVE_SPEECH_ERRORS) {
                    speechVerifierSuspendedUntilMs = nowMs + VERIFIER_SUSPENSION_MS
                    VerifierStepDecision.SuspendedBackoff(
                        reason = outcome.message.ifBlank {
                            "Offline wake verifier paused briefly after repeated speech service errors."
                        },
                        pauseMs = VERIFIER_SUSPENSION_MS
                    )
                } else {
                    VerifierStepDecision.ContinueCooldown
                }
            }
        }
    }

    companion object {
        const val WAKE_COOLDOWN_MS = 3_500L
        const val BASE_ERROR_BACKOFF_MS = 4_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val VERIFIER_SUSPENSION_MS = 60_000L
        const val MAX_CONSECUTIVE_SPEECH_ERRORS = 3
        const val MAX_AUDIO_INIT_FAILURES = 5
    }
}

class WakeWordForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var settingsCollectorJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var kwsClassifier: LiteRtKwsClassifier? = null
    private val loopController = WakeWordLoopController()

    @Volatile
    private var cachedSettings: CommanderSettings = CommanderSettings()

    @Volatile
    private var loadedKwsPath: String = ""

    private var foregroundStarted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        kwsClassifier = LiteRtKwsClassifier(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopWakeWordService(persistDisabled = false)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                stopWakeWordService(persistDisabled = true)
                return START_NOT_STICKY
            }
            ACTION_PAUSE_LISTENING -> {
                if (!foregroundStarted && !_isRunning.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _isPaused.value = true
                releaseAudioRecord()
                updateNotification("Wake word paused (microphone free)")
                return START_NOT_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                if (!foregroundStarted && !_isRunning.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _isPaused.value = false
                startMonitoringLoop()
                updateNotification("Waiting for \"Hello Buddy\" locally")
                return START_NOT_STICKY
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                _events.emit(WakeWordEvent.Error("Microphone permission required for Wake Word service."))
            }
            stopWakeWordService(persistDisabled = true)
            return START_NOT_STICKY
        }

        if (!startForegroundSafely("Waiting for \"Hello Buddy\" locally")) {
            scope.launch {
                _events.emit(WakeWordEvent.Error("Android blocked starting microphone Wake Word service from background."))
            }
            stopWakeWordService(persistDisabled = true)
            return START_NOT_STICKY
        }

        _isRunning.value = true
        _isPaused.value = false
        startMonitoringLoop()
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(statusText: String): Boolean {
        ensureNotificationChannel()
        val notification = buildNotification(statusText)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (_: SecurityException) {
            foregroundStarted = false
            false
        } catch (_: IllegalStateException) {
            foregroundStarted = false
            false
        }
    }

    private fun ensureSettingsCollector(container: com.example.AppContainer) {
        if (settingsCollectorJob?.isActive == true) return
        settingsCollectorJob = scope.launch {
            container.preferences.settings.collect { updated ->
                cachedSettings = updated
                if (updated.kwsModelPath.isNotBlank() && updated.kwsModelPath != loadedKwsPath) {
                    val loaded = kwsClassifier?.loadFromPath(updated.kwsModelPath)
                    if (loaded?.isSuccess == true) {
                        loadedKwsPath = updated.kwsModelPath
                    }
                }
            }
        }
    }

    private fun startMonitoringLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            val container = (application as CommanderApplication).container
            cachedSettings = container.preferences.settings.first()
            if (cachedSettings.kwsModelPath.isNotBlank()) {
                val res = kwsClassifier?.loadFromPath(cachedSettings.kwsModelPath)
                if (res?.isSuccess == true) {
                    loadedKwsPath = cachedSettings.kwsModelPath
                }
            }
            ensureSettingsCollector(container)

            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            var warnedBackgroundVerifier = false
            var warnedSuspension = false

            while (_isRunning.value) {
                if (_isPaused.value) {
                    _micLevel.value = 0f
                    delay(200L)
                    continue
                }

                val recorder = createRecorder(sampleRate, minBuf)
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    val abort = loopController.recordAudioInitFailure()
                    if (abort) {
                        _events.emit(WakeWordEvent.Error("Wake Word stopped: microphone hardware unavailable."))
                        stopWakeWordService(persistDisabled = true)
                        break
                    }
                    delay(1500L)
                    continue
                }

                loopController.recordAudioInitSuccess()
                audioRecord = recorder
                runCatching { recorder.startRecording() }.onFailure {
                    releaseAudioRecord()
                    if (loopController.recordAudioInitFailure()) {
                        _events.emit(WakeWordEvent.Error("Wake Word stopped: unable to capture audio."))
                        stopWakeWordService(persistDisabled = true)
                        return@launch
                    }
                    delay(1500L)
                    continue
                }

                val buffer = ShortArray(1600) // 100ms frames
                val windowFloat = FloatArray(sampleRate) // 1s window for optional KWS model
                var windowCursor = 0
                var voicedFrames = 0

                try {
                    while (_isRunning.value && !_isPaused.value) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) {
                            delay(50L)
                            continue
                        }
                        val rms = WakeWordMatcher.computeFrameRms(buffer, read)
                        _micLevel.value = (rms * 8f).coerceIn(0f, 1f)

                        // Lock-free cached settings read: zero DataStore I/O inside the real-time audio loop
                        val currentSettings = cachedSettings
                        val threshold = WakeWordMatcher.rmsThresholdForSensitivity(currentSettings.wakeWordSensitivity)

                        for (i in 0 until read) {
                            windowFloat[windowCursor] = buffer[i] / 32768f
                            windowCursor = (windowCursor + 1) % windowFloat.size
                        }

                        val now = System.currentTimeMillis()
                        if (loopController.isInCooldown(now)) continue

                        if (rms >= threshold) {
                            voicedFrames++
                        } else {
                            if (voicedFrames in 3..14) {
                                // Path A: Optional LiteRT .tflite KWS model (works foreground & background)
                                val kwsScore = kwsClassifier?.takeIf { it.isModelLoaded }
                                    ?.scoreAudioWindow(windowFloat)
                                if (kwsScore != null && kwsScore >= currentSettings.wakeWordSensitivity) {
                                    loopController.recordKwsDetection(now)
                                    triggerWakeWord("Hello Buddy", "LiteRT .tflite KWS (score=${"%.2f".format(kwsScore)})")
                                    break
                                }

                                // Path B: Foreground-eligible SpeechRecognizer fallback when no .tflite model is loaded
                                val inForeground = isAppForegroundAndInteractive(this@WakeWordForegroundService)
                                if (inForeground) {
                                    if (warnedBackgroundVerifier) {
                                        warnedBackgroundVerifier = false
                                        updateNotification("Waiting for \"Hello Buddy\" locally")
                                    }
                                }
                                if (loopController.canUseSpeechVerifier(now, inForeground)) {
                                    releaseAudioRecord()
                                    val outcome = verifyPhraseViaOfflineRecognizer(
                                        container = container,
                                        sensitivity = currentSettings.wakeWordSensitivity
                                    )
                                    when (val decision = loopController.handleSpeechOutcome(System.currentTimeMillis(), outcome)) {
                                        is VerifierStepDecision.TriggerWake -> {
                                            warnedSuspension = false
                                            triggerWakeWord(decision.transcript, "Local Acoustic VAD + Offline Speech Verifier")
                                        }
                                        VerifierStepDecision.ContinueCooldown -> Unit
                                        is VerifierStepDecision.SuspendedBackoff -> {
                                            if (!warnedSuspension) {
                                                warnedSuspension = true
                                                _events.emit(WakeWordEvent.Error(decision.reason))
                                                updateNotification("Wake verifier cooling down after service errors")
                                            }
                                        }
                                        is VerifierStepDecision.FatalPermissionStop -> {
                                            _events.emit(WakeWordEvent.Error(decision.reason))
                                            stopWakeWordService(persistDisabled = true)
                                            return@launch
                                        }
                                    }
                                    break
                                } else if (kwsClassifier?.isModelLoaded != true && !inForeground && !warnedBackgroundVerifier) {
                                    warnedBackgroundVerifier = true
                                    updateNotification("Standby: Android restricts background SpeechRecognizer without .tflite KWS")
                                }
                            }
                            voicedFrames = 0
                        }
                    }
                } finally {
                    releaseAudioRecord()
                }
            }
        }
    }

    private suspend fun verifyPhraseViaOfflineRecognizer(
        container: com.example.AppContainer,
        sensitivity: Float
    ): SpeechVerificationOutcome {
        val voiceManager = container.voiceRecognitionManager
        if (voiceManager.isCurrentlyListening() || voiceManager.isRateLimited()) {
            return SpeechVerificationOutcome.Skipped
        }
        val caps = voiceManager.state.value
        if (!caps.isRecognitionAvailable) {
            return SpeechVerificationOutcome.Skipped
        }
        val resultDeferred = kotlinx.coroutines.CompletableDeferred<SpeechVerificationOutcome>()
        voiceManager.startListening(
            language = com.example.domain.model.SpeechLanguageOption.EN_IN,
            onResult = { transcript ->
                if (!resultDeferred.isCompleted) {
                    if (WakeWordMatcher.matchesTranscript(transcript, sensitivity)) {
                        resultDeferred.complete(SpeechVerificationOutcome.Matched(transcript))
                    } else {
                        resultDeferred.complete(SpeechVerificationOutcome.NoMatch)
                    }
                }
            },
            onError = { errMessage ->
                if (!resultDeferred.isCompleted) {
                    val code = voiceManager.state.value.lastErrorCode
                    val isBenignNoSpeech = code == SpeechRecognizer.ERROR_NO_MATCH ||
                        code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (isBenignNoSpeech) {
                        resultDeferred.complete(SpeechVerificationOutcome.NoMatch)
                    } else {
                        resultDeferred.complete(SpeechVerificationOutcome.ServiceFailure(code, errMessage))
                    }
                }
            },
            allowBusyRetry = false
        )
        return (kotlinx.coroutines.withTimeoutOrNull(2600L) {
            resultDeferred.await()
        } ?: SpeechVerificationOutcome.NoMatch).also {
            voiceManager.cancelListening()
        }
    }

    private suspend fun triggerWakeWord(transcript: String, note: String) {
        _isPaused.value = true
        _micLevel.value = 0f
        _events.emit(WakeWordEvent.Detected(transcript, note))
    }

    private fun createRecorder(sampleRate: Int, bufferSize: Int): AudioRecord? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull()
    }

    private fun releaseAudioRecord() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun stopWakeWordService(persistDisabled: Boolean) {
        _isRunning.value = false
        _isPaused.value = false
        _micLevel.value = 0f
        monitorJob?.cancel()
        settingsCollectorJob?.cancel()
        releaseAudioRecord()
        if (persistDisabled) {
            scope.launch {
                runCatching {
                    (application as CommanderApplication).container.preferences.setWakeWordEnabled(false)
                }
            }
        }
        if (foregroundStarted) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            foregroundStarted = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        _isRunning.value = false
        _isPaused.value = false
        _micLevel.value = 0f
        foregroundStarted = false
        monitorJob?.cancel()
        settingsCollectorJob?.cancel()
        releaseAudioRecord()
        kwsClassifier?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_word_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.wake_word_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(statusText)) }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_PAUSE_LISTENING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WakeWordForegroundService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_full_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop Listening", pauseIntent)
            .addAction(0, "Stop Service", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "commander_wake_word"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START = "com.example.action.WAKE_START"
        const val ACTION_PAUSE_LISTENING = "com.example.action.WAKE_PAUSE"
        const val ACTION_RESUME_LISTENING = "com.example.action.WAKE_RESUME"
        const val ACTION_STOP_SERVICE = "com.example.action.WAKE_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        private val _micLevel = MutableStateFlow(0f)
        val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

        private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

        internal fun isAppForegroundAndInteractive(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val screenOn = powerManager?.isInteractive ?: true
            if (!screenOn) return false

            val info = ActivityManager.RunningAppProcessInfo()
            return runCatching {
                ActivityManager.getMyMemoryState(info)
                info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }.getOrDefault(false)
        }

        fun start(context: Context) {
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            if (_isRunning.value) {
                runCatching {
                    context.startService(Intent(context, WakeWordForegroundService::class.java).apply {
                        action = ACTION_PAUSE_LISTENING
                    })
                }.onFailure {
                    _isPaused.value = true
                }
            }
        }

        fun resume(context: Context) {
            if (_isRunning.value) {
                runCatching {
                    context.startService(Intent(context, WakeWordForegroundService::class.java).apply {
                        action = ACTION_RESUME_LISTENING
                    })
                }.onFailure {
                    _isRunning.value = false
                    _isPaused.value = false
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, WakeWordForegroundService::class.java))
            }
            _isRunning.value = false
            _isPaused.value = false
            _micLevel.value = 0f
        }
    }
}

