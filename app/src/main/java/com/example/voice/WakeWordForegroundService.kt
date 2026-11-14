package com.example.voice

import android.Manifest
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.CommanderApplication
import com.example.MainActivity
import com.example.R
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

class WakeWordForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var kwsClassifier: LiteRtKwsClassifier? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        kwsClassifier = LiteRtKwsClassifier(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopWakeWordService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_LISTENING -> {
                _isPaused.value = true
                releaseAudioRecord()
                updateNotification("Wake word paused (microphone free)")
                return START_STICKY
            }
            ACTION_RESUME_LISTENING -> {
                _isPaused.value = false
                startMonitoringLoop()
                updateNotification("Waiting for \"Hello Buddy\" locally")
                return START_STICKY
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                _events.emit(WakeWordEvent.Error("Microphone permission required for Wake Word service."))
            }
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification("Waiting for \"Hello Buddy\" locally")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isRunning.value = true
        _isPaused.value = false
        startMonitoringLoop()
        return START_STICKY
    }

    private fun startMonitoringLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            val container = (application as CommanderApplication).container
            val settings = container.preferences.settings.first()
            if (settings.kwsModelPath.isNotBlank()) {
                kwsClassifier?.loadFromPath(settings.kwsModelPath)
            }

            val sampleRate = 16_000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            while (_isRunning.value) {
                if (_isPaused.value) {
                    _micLevel.value = 0f
                    delay(200L)
                    continue
                }

                val recorder = createRecorder(sampleRate, minBuf)
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _events.emit(WakeWordEvent.Error("Microphone hardware busy or unavailable."))
                    delay(1500L)
                    continue
                }

                audioRecord = recorder
                recorder.startRecording()
                val buffer = ShortArray(1600) // 100ms frames
                val windowFloat = FloatArray(sampleRate) // 1s window for optional KWS model
                var windowCursor = 0
                var voicedFrames = 0
                var cooldownUntil = 0L

                try {
                    while (_isRunning.value && !_isPaused.value) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) {
                            delay(50L)
                            continue
                        }
                        val rms = WakeWordMatcher.computeFrameRms(buffer, read)
                        _micLevel.value = (rms * 8f).coerceIn(0f, 1f)

                        val currentSettings = container.preferences.settings.first()
                        val threshold = WakeWordMatcher.rmsThresholdForSensitivity(currentSettings.wakeWordSensitivity)

                        for (i in 0 until read) {
                            windowFloat[windowCursor] = buffer[i] / 32768f
                            windowCursor = (windowCursor + 1) % windowFloat.size
                        }

                        val now = System.currentTimeMillis()
                        if (now < cooldownUntil) continue

                        if (rms >= threshold) {
                            voicedFrames++
                        } else {
                            if (voicedFrames in 3..14) {
                                // Path A: Optional LiteRT .tflite KWS model
                                val kwsScore = kwsClassifier?.takeIf { it.isModelLoaded }
                                    ?.scoreAudioWindow(windowFloat)
                                if (kwsScore != null && kwsScore >= currentSettings.wakeWordSensitivity) {
                                    cooldownUntil = now + 3000L
                                    triggerWakeWord("Hello Buddy", "LiteRT .tflite KWS (score=${"%.2f".format(kwsScore)})")
                                    break
                                }

                                // Path B: Release mic momentarily and verify phrase via Offline SpeechRecognizer
                                cooldownUntil = now + 3500L
                                releaseAudioRecord()
                                val verified = verifyPhraseViaOfflineRecognizer(container, currentSettings.wakeWordSensitivity)
                                if (verified != null) {
                                    triggerWakeWord(verified, "Local Acoustic VAD + Offline Speech Verifier")
                                }
                                break
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
    ): String? {
        val resultDeferred = kotlinx.coroutines.CompletableDeferred<String?>()
        container.voiceRecognitionManager.startListening(
            language = com.example.domain.model.SpeechLanguageOption.EN_IN,
            onResult = { transcript ->
                if (WakeWordMatcher.matchesTranscript(transcript, sensitivity)) {
                    resultDeferred.complete(transcript)
                } else {
                    resultDeferred.complete(null)
                }
            },
            onError = {
                resultDeferred.complete(null)
            }
        )
        return kotlinx.coroutines.withTimeoutOrNull(2600L) {
            resultDeferred.await()
        }.also {
            container.voiceRecognitionManager.cancelListening()
        }
    }

    private suspend fun triggerWakeWord(transcript: String, note: String) {
        _isPaused.value = true
        _micLevel.value = 0f
        _events.emit(WakeWordEvent.Detected(transcript, note))
    }

    private fun createRecorder(sampleRate: Int, bufferSize: Int): AudioRecord? = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }.getOrNull()

    private fun releaseAudioRecord() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun stopWakeWordService() {
        _isRunning.value = false
        _isPaused.value = false
        _micLevel.value = 0f
        monitorJob?.cancel()
        releaseAudioRecord()
        scope.launch {
            runCatching {
                (application as CommanderApplication).container.preferences.setWakeWordEnabled(false)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunning.value = false
        _micLevel.value = 0f
        monitorJob?.cancel()
        releaseAudioRecord()
        kwsClassifier?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
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

        fun start(context: Context) {
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            if (_isRunning.value) {
                context.startService(Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_PAUSE_LISTENING
                })
            }
        }

        fun resume(context: Context) {
            if (_isRunning.value) {
                context.startService(Intent(context, WakeWordForegroundService::class.java).apply {
                    action = ACTION_RESUME_LISTENING
                })
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordForegroundService::class.java))
            _isRunning.value = false
            _isPaused.value = false
            _micLevel.value = 0f
        }
    }
}
