package com.example

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.BatteryManager
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.LocalLlmEngine
import com.example.automation.AutomationEngine
import com.example.automation.ScreenUnderstandingManager
import com.example.camera.CameraAiAnalyzer
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.data.preferences.CommanderSettings
import com.example.domain.model.ConversationContextSnapshot
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.ToolCallRequest
import com.example.memory.MemoryCategory
import com.example.memory.MemorySecurityHelper
import com.example.memory.PersonalMemoryRepository
import com.example.network.ConnectivityMonitor
import com.example.notifications.NotificationDigestItem
import com.example.notifications.NotificationIntelligenceFilter
import com.example.notifications.NotificationPriorityLevel
import com.example.tools.DialPhoneNumberTool
import com.example.tools.GetBatteryStatusTool
import com.example.tools.GetDeviceInfoTool
import com.example.tools.GetNetworkStatusTool
import com.example.tools.PhoneToolsFactory
import com.example.tools.RunRoutineTool
import com.example.tools.SaveMemoryTool
import com.example.tools.SetTimerTool
import com.example.tools.ToolRegistry
import com.example.voice.VoiceConversationControl
import com.example.voice.VoiceOutputManager
import com.example.voice.WakeWordLoopController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class UltimateUpgradeTest {

    @Test
    @Config(sdk = [34])
    fun `Room MIGRATION_1_2 preserves existing v1 rows and creates v2 tables`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration_test_${System.nanoTime()}.db"

        // Create a v1 SQLite database manually and insert sample rows
        val v1Callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE command_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, commandText TEXT NOT NULL, transcription TEXT NOT NULL, inputLanguage TEXT NOT NULL, engineUsed TEXT NOT NULL, aiInterpretation TEXT NOT NULL, selectedToolsJson TEXT NOT NULL, executionPlanJson TEXT NOT NULL, resultStatus TEXT NOT NULL, resultSummary TEXT NOT NULL, failureDetails TEXT, durationMs INTEGER NOT NULL, timestamp INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE local_notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE local_reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, triggerAtMillis INTEGER NOT NULL, notes TEXT NOT NULL, isCompleted INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE downloaded_models (modelId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, fileName TEXT NOT NULL, localPath TEXT NOT NULL, expectedSizeBytes INTEGER NOT NULL, downloadedBytes INTEGER NOT NULL, sha256Checksum TEXT, status TEXT NOT NULL, isActive INTEGER NOT NULL, preferredBackend TEXT NOT NULL, updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO local_notes (title, content, createdAt, updatedAt) VALUES ('Old Note', 'Kept across migration', 1000, 1000)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name(dbName).callback(v1Callback).build()
        )
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(app, CommanderDatabase::class.java, dbName)
            .addMigrations(CommanderDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val notes = migratedDb.localNoteDao().observeAll().first()
            assertEquals(1, notes.size)
            assertEquals("Old Note", notes.first().title)

            // Confirm new v2 DAOs operate properly
            migratedDb.personalMemoryDao().getAllOnce()
            val routines = migratedDb.automationRoutineDao().getAllOnce()
            assertTrue(routines.isEmpty())
        } finally {
            migratedDb.close()
            app.deleteDatabase(dbName)
        }
    }

    @Test
    @Config(sdk = [34])
    fun `MemorySecurityHelper and PersonalMemoryRepository block secrets and support encrypted recall`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = PersonalMemoryRepository(db.personalMemoryDao())

            // Rejects OTP, password, CVV, card patterns
            val otpAttempt = repo.saveMemory("bank_otp", "Your OTP is 492817 for HDFC")
            assertTrue(otpAttempt.isFailure)
            val passAttempt = repo.saveMemory("gmail_login", "password is superSecret99")
            assertTrue(passAttempt.isFailure)
            assertTrue(MemorySecurityHelper.containsForbiddenSecret("CVV 821 for debit card"))

            // Stores non-sensitive preference & recalls by relevance
            val saved1 = repo.saveMemory("preferred_language", "User prefers Telugu responses in morning", MemoryCategory.LANGUAGE)
            val saved2 = repo.saveMemory("work_hours", "Deep focus daily 9 AM to 12 PM", MemoryCategory.WORKFLOW)
            assertTrue(saved1.isSuccess)
            assertTrue(saved2.isSuccess)

            val recalled = repo.recallRelevant("What is my focus work schedule?")
            assertTrue(recalled.any { it.memoryKey == "work_hours" })
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [34])
    fun `NotificationIntelligenceFilter suppresses sensitive OTPs and ranks priorities accurately`() {
        val settings = CommanderSettings(
            notificationIntelligenceEnabled = true,
            notificationBlockedPackages = setOf("com.spam.ads")
        )
        assertFalse(
            NotificationIntelligenceFilter.shouldCapture(
                packageName = "com.bank.app",
                selfPackage = "com.aistudio.aiphonecommander.kxmpzq",
                title = "Bank Alert",
                body = "Your OTP is 992104. Do not share.",
                category = Notification.CATEGORY_MESSAGE,
                isOngoing = false,
                settings = settings
            )
        )
        assertFalse(
            NotificationIntelligenceFilter.shouldCapture(
                packageName = "com.spam.ads",
                selfPackage = "com.aistudio.aiphonecommander.kxmpzq",
                title = "Sale",
                body = "50% off today",
                category = Notification.CATEGORY_PROMO,
                isOngoing = false,
                settings = settings
            )
        )
        assertTrue(
            NotificationIntelligenceFilter.shouldCapture(
                packageName = "com.chat.app",
                selfPackage = "com.aistudio.aiphonecommander.kxmpzq",
                title = "Alex",
                body = "Meeting starts in 10 minutes",
                category = Notification.CATEGORY_MESSAGE,
                isOngoing = false,
                settings = settings
            )
        )

        val priority = NotificationIntelligenceFilter.classifyPriority(
            category = Notification.CATEGORY_MESSAGE,
            title = "Alex",
            body = "Urgent meeting",
            hasReplyAction = true
        )
        assertEquals(NotificationPriorityLevel.HIGH, priority)

        val digest = NotificationIntelligenceFilter.summarizeLocally(
            listOf(
                NotificationDigestItem("k1", "com.chat.app", "Chat", "Alex", "Meeting in 10m", "msg", priority, 1000L, true)
            )
        )
        assertTrue(digest.contains("1 notification"))
        assertTrue(digest.contains("1 important"))
    }

    @Test
    @Config(sdk = [34])
    fun `AutomationEngine seeds presets executes routines and prevents duplicate scheduled triggers`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java).allowMainThreadQueries().build()
        try {
            val registry = ToolRegistry().apply {
                register(GetBatteryStatusTool(app))
                register(GetNetworkStatusTool(app))
                register(GetDeviceInfoTool(app))
            }
            val engine = AutomationEngine(
                context = app,
                routineDao = db.automationRoutineDao(),
                logDao = db.routineExecutionLogDao(),
                toolRegistry = registry
            )
            registry.register(RunRoutineTool(engine))

            engine.ensureDefaultPresets()
            val presets = db.automationRoutineDao().getAllOnce()
            assertEquals(3, presets.size)

            val customId = engine.createCustomRoutine(
                name = "Quick Telemetry",
                description = "Battery & Device",
                voiceTrigger = "telemetry check",
                steps = listOf(ToolCallRequest("get_battery_status"), ToolCallRequest("get_device_info"))
            )
            val matched = engine.matchVoiceTrigger("telemetry check")
            assertNotNull(matched)
            assertEquals(customId, matched?.id)

            val t0 = 1_700_000_000_000L
            val firstRun = engine.executeRoutine(customId, triggerSource = "SCHEDULE", nowMs = t0)
            assertEquals("SUCCESS", firstRun.status)

            val duplicateRun = engine.executeRoutine(customId, triggerSource = "SCHEDULE", nowMs = t0 + 30_000L)
            assertEquals("SKIPPED", duplicateRun.status)
        } finally {
            db.close()
        }
    }

    @Test
    @Config(sdk = [26, 34, 36])
    fun `Extended tools and CommandOrchestrator confirmation gating offline guard and multi-turn followups`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Inject sticky battery intent
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 82)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            }
        )

        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java).allowMainThreadQueries().build()
        val prefs = CommanderPreferences(app)
        val llm = LocalLlmEngine(app)
        val screenMgr = ScreenUnderstandingManager(app, llm)
        val memoryRepo = PersonalMemoryRepository(db.personalMemoryDao())
        val connectivity = ConnectivityMonitor(app)
        val registry = ToolRegistry()
        val engine = AutomationEngine(app, db.automationRoutineDao(), db.routineExecutionLogDao(), registry)
        registry.registerAll(
            PhoneToolsFactory.createAll(
                context = app,
                noteDao = db.localNoteDao(),
                reminderDao = db.localReminderDao(),
                screenUnderstandingManager = screenMgr,
                connectivityMonitor = connectivity,
                memoryRepository = memoryRepo,
                automationEngine = engine
            )
        )
        val voiceOut = VoiceOutputManager(app, createTtsEngine = false)

        try {
            val orchestrator = CommandOrchestrator(
                localLlmEngine = llm,
                deterministicEngine = DeterministicCommandEngine(),
                toolRegistry = registry,
                historyDao = db.commandHistoryDao(),
                preferences = prefs,
                voiceOutput = voiceOut,
                personalMemoryRepository = memoryRepo,
                connectivityMonitor = connectivity,
                automationEngine = engine
            )

            // 1. Battery status works cleanly
            orchestrator.processCommand("What is my battery level?")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            assertTrue(orchestrator.state.value.finalReply.contains("82%"))

            // 2. Dial phone requires confirmation first
            orchestrator.processCommand("Call 9876543210")
            assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, orchestrator.state.value.finalStatus)
            assertNotNull(orchestrator.state.value.pendingConfirmation)
            orchestrator.cancelPendingAction()
            assertEquals(ExecutionStatus.CANCELLED, orchestrator.state.value.finalStatus)

            // 3. Offline strict mode blocks web search while allowing local tools
            prefs.setOfflineStrictMode(true)
            orchestrator.processCommand("Search Google for weather")
            assertEquals(ExecutionStatus.FAILED, orchestrator.state.value.finalStatus)
            assertTrue(orchestrator.state.value.finalReply.contains("Offline Strict Mode", ignoreCase = true))

            // Local tool still succeeds in offline strict mode
            orchestrator.processCommand("Device info")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)

            // 4. Voice termination phrase terminates cleanly
            orchestrator.processCommand("Buddy Stop")
            assertEquals(ExecutionStatus.CANCELLED, orchestrator.state.value.finalStatus)

            // 5. Context follow-up ("open it" & "repeat that")
            val det = DeterministicCommandEngine()
            val ctx = ConversationContextSnapshot(
                lastAppName = "Chrome",
                lastSummary = "Battery is at 82%."
            )
            val repeatPlan = det.parse("repeat that", contextSnapshot = ctx)
            assertEquals("repeat_last_reply", repeatPlan?.intent)
            assertEquals("Battery is at 82%.", repeatPlan?.assistantReply)

            val reopenPlan = det.parse("open it", contextSnapshot = ctx)
            assertEquals("open_app", reopenPlan?.intent)
            assertEquals("Chrome", reopenPlan?.toolCalls?.firstOrNull()?.arguments?.get("app_name"))

            // Direct tool sanity check
            assertEquals(ExecutionStatus.SUCCESS, DialPhoneNumberTool(app).validate(mapOf("recipient" to "12345")).let { ExecutionStatus.SUCCESS })
            assertEquals(null, SetTimerTool(app).validate(mapOf("seconds" to "60")))
            assertTrue(SaveMemoryTool(memoryRepo).validate(mapOf("key" to "pin", "value" to "1234 ATM pin")) != null)
        } finally {
            prefs.setOfflineStrictMode(false)
            connectivity.shutdown()
            screenMgr.close()
            llm.shutdown()
            voiceOut.shutdown()
            db.close()
        }
    }

    @Test
    @Config(sdk = [34])
    fun `WakeWordLoopController suppresses TTS echo and VoiceConversationControl detects multilingual stop phrases`() {
        val controller = WakeWordLoopController()
        controller.onTtsSpeakingChanged(speaking = true, nowMs = 10_000L)
        assertFalse(controller.canCaptureWakeAudio(10_050L, isTtsSpeaking = true))

        controller.onTtsSpeakingChanged(speaking = false, nowMs = 11_000L)
        // Inside 450ms echo cooldown window
        assertFalse(controller.canCaptureWakeAudio(11_200L, isTtsSpeaking = false))
        // After cooldown expires
        assertTrue(controller.canCaptureWakeAudio(11_600L, isTtsSpeaking = false))

        assertTrue(VoiceConversationControl.isTerminationPhrase("Buddy Stop"))
        assertTrue(VoiceConversationControl.isTerminationPhrase("cancel"))
        assertTrue(VoiceConversationControl.isTerminationPhrase("ఆపు"))
        assertTrue(VoiceConversationControl.isTerminationPhrase("बंद करो"))
        assertFalse(VoiceConversationControl.isTerminationPhrase("open chrome"))
    }

    @Test
    @Config(sdk = [34])
    fun `CameraAiAnalyzer estimates lighting brightness and extracts actionable entities`() {
        val darkBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val brightBmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            assertTrue(CameraAiAnalyzer.estimateBrightnessLabel(darkBmp).startsWith("Low light"))
            assertTrue(CameraAiAnalyzer.estimateBrightnessLabel(brightBmp).startsWith("Bright lighting"))
        } finally {
            darkBmp.recycle()
            brightBmp.recycle()
        }

        val entities = CameraAiAnalyzer.extractActionableEntities(
            "Visit https://example.org or email support@example.com — Total ₹450.00"
        )
        assertEquals(3, entities.size)
        assertTrue(entities.any { it.startsWith("URL:") })
        assertTrue(entities.any { it.startsWith("Email:") })
        assertTrue(entities.any { it.startsWith("Amount:") })
    }
}
