package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CommanderApplication
import com.example.ai.DownloadProgressState
import com.example.ai.ExecutionUiState
import com.example.ai.LanguageDetector
import com.example.ai.ModelCatalog
import com.example.data.local.CommandHistoryEntity
import com.example.data.local.DownloadedModelEntity
import com.example.data.local.LocalNoteEntity
import com.example.data.local.LocalReminderEntity
import com.example.data.preferences.CommanderSettings
import com.example.domain.model.DeviceCompatibilityInfo
import com.example.domain.model.LocalModelSpec
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.OrbState
import com.example.domain.model.SpeechLanguageOption
import com.example.domain.model.ToolSchemaInfo
import com.example.voice.ActivationSoundPlayer
import com.example.voice.VoiceListeningState
import com.example.voice.WakeWordEvent
import com.example.voice.WakeWordForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionStatusSnapshot(
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val writeSettingsGranted: Boolean = false,
    val screenCaptureReady: Boolean = false
)

class CommanderViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as CommanderApplication).container

    val executionState: StateFlow<ExecutionUiState> = container.commandOrchestrator.state
    val settings: StateFlow<CommanderSettings> = container.preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CommanderSettings())

    val modelRuntimeState: StateFlow<ModelRuntimeState> = container.localLlmEngine.runtimeState
    val downloadProgress: StateFlow<Map<String, DownloadProgressState>> = container.modelDownloadManager.progress
    val downloadedModels: StateFlow<List<DownloadedModelEntity>> = container.modelDownloadManager.downloadedModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val voiceListeningState: StateFlow<VoiceListeningState> = container.voiceRecognitionManager.state
    val ttsSpeaking: StateFlow<Boolean> = container.voiceOutputManager.isSpeaking
    val ttsSpeechLevel: StateFlow<Float> = container.voiceOutputManager.speechLevel
    val ttsVoices = container.voiceOutputManager.offlineVoices

    val wakeWordServiceRunning: StateFlow<Boolean> = WakeWordForegroundService.isRunning
    val wakeWordMicLevel: StateFlow<Float> = WakeWordForegroundService.micLevel

    private val _historySearch = MutableStateFlow("")
    val historySearch: StateFlow<String> = _historySearch.asStateFlow()

    val historyEntries: StateFlow<List<CommandHistoryEntity>> = combine(
        _historySearch,
        container.database.commandHistoryDao().observeAll()
    ) { query, list ->
        if (query.isBlank()) list else list.filter {
            it.commandText.contains(query, ignoreCase = true) ||
                it.resultSummary.contains(query, ignoreCase = true) ||
                it.aiInterpretation.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentCommands: StateFlow<List<CommandHistoryEntity>> =
        container.database.commandHistoryDao().observeRecent(5)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localNotes: StateFlow<List<LocalNoteEntity>> =
        container.database.localNoteDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localReminders: StateFlow<List<LocalReminderEntity>> =
        container.database.localReminderDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deviceCompatibility = MutableStateFlow(
        DeviceCompatibilityInfo(
            totalRamMb = 0L,
            availableRamMb = 0L,
            availableStorageMb = 0L,
            cpuAbi = "detecting…",
            isArm64 = false,
            supportsGpuAcceleration = false,
            tier = com.example.domain.model.DeviceTier.LOW_RAM
        )
    )
    val deviceCompatibility: StateFlow<DeviceCompatibilityInfo> = _deviceCompatibility.asStateFlow()

    private val _modelStorageUsedMb = MutableStateFlow(0L)
    val modelStorageUsedMb: StateFlow<Long> = _modelStorageUsedMb.asStateFlow()

    private val _wakeBannerMessage = MutableStateFlow<String?>(null)
    val wakeBannerMessage: StateFlow<String?> = _wakeBannerMessage.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    val catalogModels: List<LocalModelSpec> = ModelCatalog.publicModels
    val registeredToolSchemas: List<ToolSchemaInfo> = container.toolRegistry.allSchemas()

    init {
        refreshDeviceCompatibility()

        viewModelScope.launch {
            settings.collect { s ->
                container.voiceOutputManager.configure(s.speechSpeed, s.selectedTtsVoice)
                container.localLlmEngine.setCommandOnlyMode(s.commandOnlyMode)
            }
        }

        // Keep model storage metric synced cleanly in background when models finish downloading or are deleted
        viewModelScope.launch {
            downloadedModels.collect {
                val bytes = container.modelDownloadManager.getTotalStorageUsedBytes()
                _modelStorageUsedMb.value = bytes / (1024L * 1024L)
            }
        }

        // Observe wake word events from WakeWordForegroundService
        viewModelScope.launch {
            WakeWordForegroundService.events.collect { event ->
                when (event) {
                    is WakeWordEvent.Detected -> handleWakeWordTriggered(event)
                    is WakeWordEvent.Error -> _snackbarMessage.value = event.message
                }
            }
        }
    }

    private fun handleWakeWordTriggered(event: WakeWordEvent.Detected) {
        viewModelScope.launch {
            val currentSettings = settings.value
            val activeLang = currentSettings.speechLanguage.appLanguage
                ?: executionState.value.activeConversationLanguage
            _wakeBannerMessage.value = "Hello Buddy detected (${event.confidenceNote})"
            container.commandOrchestrator.updateOrbState(OrbState.WAKE_WORD_DETECTED)
            if (currentSettings.activationSoundEnabled) {
                ActivationSoundPlayer.playChime()
            }
            if (currentSettings.voiceResponseEnabled) {
                container.voiceOutputManager.setResponseLanguage(activeLang)
                val greeting = LanguageDetector.localizedGreetingPrompt(activeLang)
                container.voiceOutputManager.speak(greeting) {
                    startVoiceCapture(afterWakeWord = true)
                }
            } else {
                startVoiceCapture(afterWakeWord = true)
            }
        }
    }

    fun submitCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        if (executionState.value.isExecuting) {
            _snackbarMessage.value = "Command execution in progress…"
            return
        }
        viewModelScope.launch {
            container.commandOrchestrator.processCommand(
                rawCommand = trimmed,
                inputLanguage = settings.value.speechLanguage.name
            )
        }
    }

    fun retryLastCommand() {
        val last = executionState.value.currentCommand.trim()
        if (last.isNotBlank()) {
            submitCommand(last)
        }
    }

    fun startVoiceCapture(afterWakeWord: Boolean = false) {
        if (executionState.value.isExecuting) {
            _snackbarMessage.value = "Please wait for the current command to finish."
            return
        }
        val context = getApplication<Application>()
        if (wakeWordServiceRunning.value) {
            WakeWordForegroundService.pause(context)
        }
        container.voiceOutputManager.stop()
        container.commandOrchestrator.updateOrbState(OrbState.LISTENING)
        _wakeBannerMessage.value = if (afterWakeWord) "I'm listening…" else null

        container.voiceRecognitionManager.startListening(
            language = settings.value.speechLanguage,
            onResult = { transcript ->
                _wakeBannerMessage.value = null
                viewModelScope.launch {
                    container.commandOrchestrator.processCommand(
                        rawCommand = transcript,
                        inputLanguage = settings.value.speechLanguage.name,
                        onComplete = {
                            if (settings.value.wakeWordEnabled && settings.value.continuousListeningEnabled) {
                                WakeWordForegroundService.resume(context)
                            }
                        }
                    )
                }
            },
            onError = { errMsg ->
                _wakeBannerMessage.value = null
                container.commandOrchestrator.updateOrbState(OrbState.ERROR)
                _snackbarMessage.value = errMsg
                if (settings.value.wakeWordEnabled && settings.value.continuousListeningEnabled) {
                    WakeWordForegroundService.resume(context)
                }
            }
        )
    }

    fun stopVoiceCapture() {
        container.voiceRecognitionManager.stopListening()
    }

    fun cancelVoiceCapture() {
        container.voiceRecognitionManager.cancelListening()
        container.commandOrchestrator.updateOrbState(OrbState.IDLE)
        _wakeBannerMessage.value = null
    }

    fun confirmPendingAction() {
        viewModelScope.launch { container.commandOrchestrator.confirmPendingAction() }
    }

    fun cancelPendingAction() {
        viewModelScope.launch { container.commandOrchestrator.cancelPendingAction() }
    }

    // Wake Word & Voice Settings
    fun toggleWakeWord(enabled: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            container.preferences.setWakeWordEnabled(enabled)
            runCatching {
                if (enabled) {
                    WakeWordForegroundService.start(context)
                } else {
                    WakeWordForegroundService.stop(context)
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.message ?: "Unable to toggle Wake Word service"
            }
        }
    }

    fun setWakeWordSensitivity(value: Float) {
        viewModelScope.launch { container.preferences.setWakeWordSensitivity(value) }
    }

    fun setActivationSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferences.setActivationSoundEnabled(enabled) }
    }

    fun setVoiceResponseEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferences.setVoiceResponseEnabled(enabled) }
    }

    fun setContinuousListeningEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferences.setContinuousListeningEnabled(enabled) }
    }

    fun setSpeechSpeed(speed: Float) {
        viewModelScope.launch { container.preferences.setSpeechSpeed(speed) }
    }

    fun setSelectedTtsVoice(voiceName: String) {
        viewModelScope.launch { container.preferences.setSelectedTtsVoice(voiceName) }
    }

    fun setSpeechLanguage(option: SpeechLanguageOption) {
        viewModelScope.launch { container.preferences.setSpeechLanguage(option) }
    }

    // Model Management
    fun refreshDeviceCompatibility() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                container.deviceCompatibilityManager.inspectDevice()
            }
            _deviceCompatibility.value = info
            val bytes = container.modelDownloadManager.getTotalStorageUsedBytes()
            _modelStorageUsedMb.value = bytes / (1024L * 1024L)
        }
    }

    fun startOrResumeDownload(spec: LocalModelSpec) {
        container.modelDownloadManager.startOrResumeDownload(spec)
    }

    fun pauseDownload(modelId: String) {
        container.modelDownloadManager.pauseDownload(modelId)
    }

    fun validateModel(modelId: String) {
        viewModelScope.launch {
            val res = container.modelDownloadManager.validateInstalledModel(modelId)
            _snackbarMessage.value = res.getOrElse { it.message ?: "Validation failed" }
        }
    }

    fun loadModel(entity: DownloadedModelEntity, preferGpu: Boolean) {
        viewModelScope.launch {
            val compat = withContext(Dispatchers.IO) {
                container.deviceCompatibilityManager.inspectDevice()
            }
            _deviceCompatibility.value = compat
            val spec = ModelCatalog.publicModels.firstOrNull { it.id == entity.modelId }
            val minRam = spec?.minRamMb ?: 3072L
            if (compat.totalRamMb < minRam) {
                _snackbarMessage.value = "Cannot load ${entity.displayName}: requires ${minRam} MB RAM (device has ${compat.totalRamMb} MB)."
                return@launch
            }
            val result = container.localLlmEngine.loadModel(
                modelId = entity.modelId,
                displayName = entity.displayName,
                modelPath = entity.localPath,
                preferGpu = preferGpu && compat.supportsGpuAcceleration,
                toolSchemas = container.toolRegistry.allSchemas()
            )
            result.fold(
                onSuccess = {
                    container.database.downloadedModelDao().setActiveModel(entity.modelId)
                    container.preferences.setCommandOnlyMode(false)
                    _snackbarMessage.value = "${entity.displayName} loaded locally."
                },
                onFailure = { err ->
                    _snackbarMessage.value = "Model load failed: ${err.message}. Falling back to Deterministic Command Engine."
                }
            )
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            container.localLlmEngine.unloadModel()
            container.database.downloadedModelDao().clearActiveModel()
            _snackbarMessage.value = "Model unloaded. Deterministic Command Engine active."
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (modelRuntimeState.value.activeModelId == modelId) {
                container.localLlmEngine.unloadModel()
            }
            container.modelDownloadManager.deleteModel(modelId)
            refreshDeviceCompatibility()
            _snackbarMessage.value = "Model deleted from local storage."
        }
    }

    fun setCommandOnlyMode(enabled: Boolean) {
        viewModelScope.launch {
            container.preferences.setCommandOnlyMode(enabled)
            if (enabled) {
                container.localLlmEngine.setCommandOnlyMode(true)
            }
        }
    }

    fun importLocalLlmModel(uri: Uri) {
        viewModelScope.launch {
            val res = container.modelDownloadManager.importLocalModel(uri)
            refreshDeviceCompatibility()
            _snackbarMessage.value = res.fold(
                onSuccess = { "Imported ${it.displayName} locally." },
                onFailure = { it.message ?: "Import failed" }
            )
        }
    }

    fun importKwsModel(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val dest = java.io.File(context.filesDir, "hello_buddy_kws.tflite")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Cannot read selected .tflite file")
                }
                container.preferences.setKwsModelPath(dest.absolutePath)
            }.fold(
                onSuccess = { _snackbarMessage.value = "Custom Hello Buddy .tflite KWS model installed locally." },
                onFailure = { _snackbarMessage.value = it.message ?: "Failed to import KWS model" }
            )
        }
    }

    // Screen Capture Consent handoff
    fun onScreenCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            container.screenUnderstandingManager.setProjectionGrant(resultCode, data)
            submitCommand("Explain my screen")
        } else {
            _snackbarMessage.value = "Screen capture permission was cancelled."
        }
    }

    // History & Productivity
    fun setHistorySearch(query: String) {
        _historySearch.value = query
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { container.database.commandHistoryDao().deleteById(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            container.database.commandHistoryDao().clearAll()
            _snackbarMessage.value = "Command history cleared."
        }
    }

    suspend fun exportHistoryText(): String {
        val all = container.database.commandHistoryDao().getAllOnce()
        return buildString {
            appendLine("AI Phone Commander — Local Command History Export")
            appendLine("=================================================")
            all.forEach { entry ->
                appendLine("[${java.util.Date(entry.timestamp)}] (${entry.resultStatus}) [${entry.engineUsed}]")
                appendLine("Command: ${entry.commandText}")
                appendLine("Interpretation: ${entry.aiInterpretation}")
                appendLine("Result: ${entry.resultSummary}")
                if (!entry.failureDetails.isNullOrBlank()) appendLine("Failure: ${entry.failureDetails}")
                appendLine("---")
            }
        }
    }

    fun toggleReminderCompleted(id: Long, completed: Boolean) {
        viewModelScope.launch { container.database.localReminderDao().setCompleted(id, completed) }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { container.database.localNoteDao().deleteById(id) }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch { container.database.localReminderDao().deleteById(id) }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun openAccessibilitySettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _snackbarMessage.value = "Unable to open Accessibility settings on this device."
        }
    }

    fun openWriteSettings() {
        val context = getApplication<Application>()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _snackbarMessage.value = "Unable to open Modify System Settings screen."
        }
    }

    fun openOfflineSpeechSettings() {
        val context = getApplication<Application>()
        runCatching {
            val intent = Intent("com.android.settings.VOICE_INPUT_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(if (intent.resolveActivity(context.packageManager) != null) intent else fallback)
        }.onFailure {
            _snackbarMessage.value = "Unable to open Voice Input settings."
        }
    }
}
