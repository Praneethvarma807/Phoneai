package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.LanguageDetector
import com.example.ai.LocalLlmEngine
import com.example.automation.DocumentAccessManager
import com.example.automation.ReminderMechanism
import com.example.automation.ReminderScheduler
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.local.LocalReminderEntity
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.AppLanguage
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.SpeechLanguageOption
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.voice.SpeechVerificationOutcome
import com.example.voice.VoiceOutputManager
import com.example.voice.VoiceRecognitionManager
import com.example.voice.WakeWordForegroundService
import com.example.voice.WakeWordLoopController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class Phase4EndToEndValidationTest {

    @Test
    @Config(sdk = [26, 28, 30, 33, 34, 36])
    fun `reminder scheduling and notification channel work across Android API 26 to 36`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val scheduler = ReminderScheduler(app)
        val result = scheduler.scheduleReminder(
            LocalReminderEntity(
                id = 77L,
                title = "Compatibility Check",
                triggerAtMillis = System.currentTimeMillis() + 600_000L
            )
        )
        assertTrue(result.notificationPermissionGranted)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(nm.getNotificationChannel(ReminderScheduler.REMINDER_CHANNEL_ID))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertEquals(ReminderMechanism.EXACT_ALARM, result.mechanism)
        }
        scheduler.cancelReminder(77L)
    }

    @Test
    @Config(sdk = [26, 30, 34, 36])
    fun `wake word service and background SpeechRecognizer compliance across API 26 to 36`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val controller = WakeWordLoopController(sdkIntProvider = { Build.VERSION.SDK_INT })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertFalse(controller.canUseSpeechVerifier(10_000L, isAppInForeground = false))
            assertTrue(controller.canUseSpeechVerifier(10_000L, isAppInForeground = true))
        } else {
            assertTrue(controller.canUseSpeechVerifier(10_000L, isAppInForeground = false))
        }

        // Start & stop explicit intent stays consistent across API levels
        val svcController = Robolectric.buildService(WakeWordForegroundService::class.java).create()
        val svc = svcController.get()
        val stopResult = svc.onStartCommand(Intent(WakeWordForegroundService.ACTION_STOP_SERVICE), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, stopResult)
        svcController.destroy()

        // Ensure benign verifier outcomes do not poison streak
        controller.handleSpeechOutcome(20_000L, SpeechVerificationOutcome.Matched("hello buddy"))
        assertEquals(0, controller.consecutiveSpeechErrors)
    }

    @Test
    @Config(sdk = [36])
    fun `full 6-language plus mixed and invalid commands execute end to end and persist Room history`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = CommanderPreferences(app)
        prefs.setVoiceResponseEnabled(false)

        val llm = LocalLlmEngine(app)
        val screenManager = ScreenUnderstandingManager(app, llm)
        val docManager = DocumentAccessManager(app, prefs)
        val reminderScheduler = ReminderScheduler(app)
        val registry = ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = app,
                    noteDao = db.localNoteDao(),
                    reminderDao = db.localReminderDao(),
                    screenUnderstandingManager = screenManager,
                    reminderScheduler = reminderScheduler,
                    documentAccessManager = docManager
                )
            )
        }
        val voiceOutput = VoiceOutputManager(app, createTtsEngine = false)
        val orchestrator = CommandOrchestrator(
            localLlmEngine = llm,
            deterministicEngine = DeterministicCommandEngine(),
            toolRegistry = registry,
            historyDao = db.commandHistoryDao(),
            preferences = prefs,
            voiceOutput = voiceOutput
        )

        // 1. English Note creation
        orchestrator.processCommand("Create a note titled Standup content Ship v1.0.1")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.ENGLISH, orchestrator.state.value.activeConversationLanguage)

        // 2. Telugu native reminder
        orchestrator.processCommand("రిమైండర్ పెట్టు మందులు వేసుకో")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.TELUGU, orchestrator.state.value.activeConversationLanguage)

        // 3. Romanized Telugu + English mixed-language WhatsApp draft (Confirmation gate)
        orchestrator.processCommand("Mom ki WhatsApp message chey running late అని")
        assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, orchestrator.state.value.finalStatus)
        orchestrator.cancelPendingAction()

        // 4. Hindi settings command
        orchestrator.processCommand("वाईफाई सेटिंग्स खोलो")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.HINDI, orchestrator.state.value.activeConversationLanguage)

        // 5. Tamil volume command
        orchestrator.processCommand("ஒலியை கூட்டு")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.TAMIL, orchestrator.state.value.activeConversationLanguage)

        // 6. Kannada volume command
        orchestrator.processCommand("ವಾಲ್ಯೂಮ್ ಕಡಿಮೆ ಮಾಡು")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.KANNADA, orchestrator.state.value.activeConversationLanguage)

        // 7. Malayalam settings command
        orchestrator.processCommand("ബ്ലൂടൂത്ത് തുറക്കൂ")
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
        assertEquals(AppLanguage.MALAYALAM, orchestrator.state.value.activeConversationLanguage)

        // 8. Unsupported / unrecognized input behaves honestly
        orchestrator.processCommand("Explain string theory equations")
        assertEquals(ExecutionStatus.UNSUPPORTED, orchestrator.state.value.finalStatus)

        // Verify Room persistence
        assertEquals(1, db.localNoteDao().observeAll().first().size)
        assertEquals(1, db.localReminderDao().observeAll().first().size)
        assertTrue(db.commandHistoryDao().getAllOnce().size >= 7)

        // Verify DataStore settings persistence across SpeechLanguageOption values
        prefs.setSpeechLanguage(SpeechLanguageOption.TA_IN)
        assertEquals(SpeechLanguageOption.TA_IN, prefs.settings.first().speechLanguage)

        // Verify LanguageDetector covers all 6 languages accurately
        assertEquals(AppLanguage.TELUGU, LanguageDetector.detect("మంచి రోజు").primaryLanguage)
        assertEquals(AppLanguage.HINDI, LanguageDetector.detect("कृपया वाईफाई खोलो").primaryLanguage)
        assertEquals(AppLanguage.TAMIL, LanguageDetector.detect("அமைப்புகள் திற").primaryLanguage)
        assertEquals(AppLanguage.KANNADA, LanguageDetector.detect("ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆಯಿರಿ").primaryLanguage)
        assertEquals(AppLanguage.MALAYALAM, LanguageDetector.detect("സെറ്റിംഗ്സ് തുറക്കൂ").primaryLanguage)

        val vr = VoiceRecognitionManager(app)
        vr.refreshCapabilitiesAsync(SpeechLanguageOption.AUTO)
        vr.shutdown()
        voiceOutput.shutdown()
        screenManager.close()
        llm.shutdown()
        db.close()
    }
}
