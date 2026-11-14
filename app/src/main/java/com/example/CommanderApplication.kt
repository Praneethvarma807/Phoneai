package com.example

import android.app.Application
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.DeviceCompatibilityManager
import com.example.ai.LocalLlmEngine
import com.example.ai.ModelDownloadManager
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.voice.VoiceOutputManager
import com.example.voice.VoiceRecognitionManager

class AppContainer(application: Application) {
    val database: CommanderDatabase = CommanderDatabase.build(application)
    val preferences: CommanderPreferences = CommanderPreferences(application)
    val deviceCompatibilityManager: DeviceCompatibilityManager = DeviceCompatibilityManager(application)
    val modelDownloadManager: ModelDownloadManager = ModelDownloadManager(
        context = application,
        dao = database.downloadedModelDao(),
        compatibilityManager = deviceCompatibilityManager
    )
    val localLlmEngine: LocalLlmEngine = LocalLlmEngine(application)
    val deterministicEngine: DeterministicCommandEngine = DeterministicCommandEngine()
    val screenUnderstandingManager: ScreenUnderstandingManager = ScreenUnderstandingManager(
        context = application,
        localLlmEngine = localLlmEngine
    )
    val toolRegistry: ToolRegistry = ToolRegistry().apply {
        registerAll(
            PhoneToolsFactory.createAll(
                context = application,
                noteDao = database.localNoteDao(),
                reminderDao = database.localReminderDao(),
                screenUnderstandingManager = screenUnderstandingManager
            )
        )
    }
    val voiceOutputManager: VoiceOutputManager = VoiceOutputManager(application)
    val voiceRecognitionManager: VoiceRecognitionManager = VoiceRecognitionManager(application)
    val commandOrchestrator: CommandOrchestrator = CommandOrchestrator(
        localLlmEngine = localLlmEngine,
        deterministicEngine = deterministicEngine,
        toolRegistry = toolRegistry,
        historyDao = database.commandHistoryDao(),
        preferences = preferences,
        voiceOutput = voiceOutputManager
    )
}

class CommanderApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.localLlmEngine.shutdown()
        container.modelDownloadManager.shutdown()
        container.screenUnderstandingManager.close()
        container.voiceOutputManager.shutdown()
        container.voiceRecognitionManager.shutdown()
        super.onTerminate()
    }
}
