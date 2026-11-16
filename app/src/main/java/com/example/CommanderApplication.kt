package com.example

import android.app.Application
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.DeviceCompatibilityManager
import com.example.ai.LocalLlmEngine
import com.example.ai.ModelDownloadManager
import com.example.automation.DocumentAccessManager
import com.example.automation.ReminderScheduler
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.voice.VoiceOutputManager
import com.example.voice.VoiceRecognitionManager

class AppContainer(application: Application) {
    val database: CommanderDatabase by lazy { CommanderDatabase.build(application) }
    val preferences: CommanderPreferences by lazy { CommanderPreferences(application) }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(application) }
    val documentAccessManager: DocumentAccessManager by lazy {
        DocumentAccessManager(context = application, preferences = preferences)
    }
    val deviceCompatibilityManager: DeviceCompatibilityManager by lazy { DeviceCompatibilityManager(application) }
    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(
            context = application,
            dao = database.downloadedModelDao(),
            compatibilityManager = deviceCompatibilityManager
        )
    }
    val localLlmEngine: LocalLlmEngine by lazy { LocalLlmEngine(application) }
    val deterministicEngine: DeterministicCommandEngine by lazy { DeterministicCommandEngine() }
    val screenUnderstandingManager: ScreenUnderstandingManager by lazy {
        ScreenUnderstandingManager(
            context = application,
            localLlmEngine = localLlmEngine
        )
    }
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = application,
                    noteDao = database.localNoteDao(),
                    reminderDao = database.localReminderDao(),
                    screenUnderstandingManager = screenUnderstandingManager,
                    reminderScheduler = reminderScheduler,
                    documentAccessManager = documentAccessManager
                )
            )
        }
    }
    val voiceOutputManager: VoiceOutputManager by lazy { VoiceOutputManager(application) }
    val voiceRecognitionManager: VoiceRecognitionManager by lazy { VoiceRecognitionManager(application) }
    val commandOrchestrator: CommandOrchestrator by lazy {
        CommandOrchestrator(
            localLlmEngine = localLlmEngine,
            deterministicEngine = deterministicEngine,
            toolRegistry = toolRegistry,
            historyDao = database.commandHistoryDao(),
            preferences = preferences,
            voiceOutput = voiceOutputManager
        )
    }
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
