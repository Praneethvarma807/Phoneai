package com.example

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.DeviceCompatibilityManager
import com.example.ai.LanguageDetector
import com.example.ai.LocalLlmEngine
import com.example.ai.ModelDownloadManager
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.ActionCategory
import com.example.domain.model.AppLanguage
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.LanguageDetectionResult
import com.example.domain.model.SpeechLanguageOption
import com.example.domain.model.ToolResult
import com.example.domain.model.ToolSchemaInfo
import com.example.tools.CommanderTool
import com.example.tools.ComposeSmsTool
import com.example.tools.PhoneToolsFactory
import com.example.tools.SetAlarmTool
import com.example.tools.SetTimerTool
import com.example.tools.ToolRegistry
import com.example.tools.WebSearchTool
import com.example.ui.components.sanitizeUiDiagnostics
import com.example.voice.SpeechRecognizerFactory
import com.example.voice.SpeechRecognizerHandle
import com.example.voice.VoiceOutputManager
import com.example.voice.VoiceRecognitionManager
import com.example.voice.WakeWordMatcher
import java.io.File
import java.util.concurrent.Executor
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
@Config(sdk = [34])
class EndToEndHardeningValidationTest {

    private class HardeningRecognizerHandle : SpeechRecognizerHandle {
        var listener: RecognitionListener? = null
        var destroyed = false
        var startBehavior: ((HardeningRecognizerHandle, Intent) -> Unit)? = null
        var downloadBehavior: ((ModelDownloadListener?) -> Boolean)? = null

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            startBehavior?.invoke(this, intent)
        }

        override fun stopListening() {}
        override fun cancel() {}
        override fun destroy() {
            destroyed = true
        }

        override fun checkRecognitionSupport(
            intent: Intent,
            executor: Executor,
            callback: RecognitionSupportCallback
        ) {
            executor.execute { callback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) }
        }

        override fun triggerModelDownload(
            intent: Intent,
            executor: Executor,
            listener: ModelDownloadListener?
        ): Boolean = downloadBehavior?.invoke(listener) ?: true
    }

    private class HardeningSpeechFactory : SpeechRecognizerFactory {
        var onDeviceAvailable = true
        var recognitionAvailable = true
        var hasInternet = true
        var nextOnDevice: () -> HardeningRecognizerHandle? = { HardeningRecognizerHandle() }
        var nextDefault: () -> HardeningRecognizerHandle? = { HardeningRecognizerHandle() }

        override fun isRecognitionAvailable(context: Context) = recognitionAvailable
        override fun isOnDeviceRecognitionAvailable(context: Context) = onDeviceAvailable
        override fun discoverServices(context: Context) = emptyList<android.content.ComponentName>()
        override fun hasInternetConnection(context: Context) = hasInternet
        override fun isRecordAudioGranted(context: Context) = true
        override fun createOnDeviceRecognizer(context: Context): SpeechRecognizerHandle? = nextOnDevice()
        override fun createDefaultRecognizer(context: Context): SpeechRecognizerHandle? = nextDefault()
        override fun createComponentRecognizer(
            context: Context,
            componentName: android.content.ComponentName
        ): SpeechRecognizerHandle? = null
    }

    private fun drainMain(ms: Long = 500L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Test
    fun `voice recognition Error 11 cooldown recovers via resetTemporaryDisqualification and retries transient errors once`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = HardeningSpeechFactory()
        var defaultCalls = 0
        factory.nextOnDevice = {
            HardeningRecognizerHandle().apply {
                startBehavior = { h, _ -> h.listener?.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED) }
            }
        }
        factory.nextDefault = {
            HardeningRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    defaultCalls++
                    if (defaultCalls == 1) {
                        h.listener?.onError(SpeechRecognizer.ERROR_AUDIO)
                    } else {
                        h.listener?.onReadyForSpeech(Bundle())
                        h.listener?.onResults(Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("open settings"))
                        })
                    }
                }
            }
        }

        val manager = VoiceRecognitionManager(app, factory)
        drainMain(100)

        var transcript: String? = null
        manager.startListening(SpeechLanguageOption.EN_IN, onResult = { transcript = it }, onError = {})
        drainMain(1500)

        assertEquals("open settings", transcript)
        assertEquals(2, defaultCalls) // 1 transient ERROR_AUDIO + 1 retry success
        assertFalse(manager.checkCapabilities().isOfflineRecognitionSupported) // on-device in cooldown

        // Explicit refresh resets cooldown cleanly
        manager.resetTemporaryDisqualification()
        assertTrue(manager.checkCapabilities().isOfflineRecognitionSupported)
        manager.shutdown()
    }

    @Test
    fun `voice recognition language model download handle cleans up without leaking on API 34`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = HardeningSpeechFactory()
        val downloadHandle = HardeningRecognizerHandle().apply {
            downloadBehavior = { listener ->
                listener?.onSuccess()
                true
            }
        }
        factory.nextOnDevice = { downloadHandle }
        val manager = VoiceRecognitionManager(app, factory)
        drainMain(100)

        val triggered = manager.triggerLanguageModelDownload(SpeechLanguageOption.TE_IN)
        drainMain(200)
        assertTrue(triggered)
        assertTrue(downloadHandle.destroyed)
        manager.shutdown()
    }

    @Test
    fun `command orchestrator preserves PERMISSION_REQUIRED when step 2 needs accessibility and invokes onComplete on all paths`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = CommanderPreferences(app)
        prefs.setVoiceResponseEnabled(false)

        val llm = LocalLlmEngine(app)
        val screenManager = ScreenUnderstandingManager(app, llm)
        val registry = ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = app,
                    noteDao = db.localNoteDao(),
                    reminderDao = db.localReminderDao(),
                    screenUnderstandingManager = screenManager
                )
            )
            // Override open_url so step 1 returns SUCCESS reliably in test
            register(object : CommanderTool {
                override val category = ActionCategory.BROWSER
                override val schema = ToolSchemaInfo("open_url", "test open url", emptyList(), false, emptyMap(), category.name)
                override suspend fun execute(arguments: Map<String, String>) =
                    ToolResult("open_url", ExecutionStatus.SUCCESS, "Opened test URL")
            })
            // Step 2 returns Accessibility PERMISSION_REQUIRED
            register(object : CommanderTool {
                override val category = ActionCategory.VISION
                override val schema = ToolSchemaInfo("get_screen_text", "test screen", emptyList(), false, emptyMap(), category.name)
                override suspend fun execute(arguments: Map<String, String>) =
                    ToolResult("get_screen_text", ExecutionStatus.PERMISSION_REQUIRED, "Accessibility needed", requiresAccessibility = true)
            })
        }
        val voiceOut = VoiceOutputManager(app, createTtsEngine = false)
        val orchestrator = CommandOrchestrator(
            localLlmEngine = llm,
            deterministicEngine = DeterministicCommandEngine(),
            toolRegistry = registry,
            historyDao = db.commandHistoryDao(),
            preferences = prefs,
            voiceOutput = voiceOut
        )

        // Empty command still triggers onComplete
        var emptyCompleted = false
        orchestrator.processCommand("   ") { emptyCompleted = true }
        assertTrue(emptyCompleted)

        // Multi-step command where step 1 succeeds and step 2 requires Accessibility must NOT report SUCCESS
        var applyCompleted = false
        orchestrator.processCommand("Open Naukri and search for Kotlin developer") { applyCompleted = true }
        assertTrue(applyCompleted)
        assertEquals(ExecutionStatus.PERMISSION_REQUIRED, orchestrator.state.value.finalStatus)
        assertTrue(orchestrator.state.value.stepResults.any { it.requiresAccessibility })

        // Confirmation required path + cancel path both invoke onComplete
        var confirmPromptDone = false
        orchestrator.processCommand("Draft SMS to +15559876543 saying Hello") { confirmPromptDone = true }
        assertTrue(confirmPromptDone)
        assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, orchestrator.state.value.finalStatus)

        var cancelDone = false
        orchestrator.cancelPendingAction { cancelDone = true }
        assertTrue(cancelDone)
        assertEquals(ExecutionStatus.CANCELLED, orchestrator.state.value.finalStatus)

        voiceOut.shutdown()
        screenManager.close()
        llm.shutdown()
        db.close()
    }

    @Test
    fun `wake word matcher matches native Indic scripts across Telugu Hindi Tamil Kannada Malayalam`() {
        assertTrue(WakeWordMatcher.matchesTranscript("హలో బడ్డీ open youtube"))
        assertTrue(WakeWordMatcher.matchesTranscript("हेलो बड्डी बैटरी बताओ"))
        assertTrue(WakeWordMatcher.matchesTranscript("ஹலோ பட்டி open settings"))
        assertTrue(WakeWordMatcher.matchesTranscript("ಹಲೋ ಬಡ್ಡಿ"))
        assertTrue(WakeWordMatcher.matchesTranscript("ഹലോ ബഡ്ഡി"))
        assertFalse(WakeWordMatcher.matchesTranscript("random unrelated sentence"))
    }

    @Test
    fun `deterministic command engine avoids fabricated defaults and false substring triggers`() {
        val engine = DeterministicCommandEngine()
        val en = LanguageDetectionResult(
            primaryLanguage = AppLanguage.ENGLISH,
            isMixed = false,
            badgeLabel = "EN"
        )

        // Incomplete commands ask for missing details without inventing defaults
        val incompleteInputs = listOf(
            "Update my Naukri profile headline",
            "Search Naukri jobs",
            "Set timer",
            "Set alarm",
            "Send SMS",
            "Draft email",
            "Create reminder",
            "Create note",
            "Search web"
        )
        incompleteInputs.forEach { cmd ->
            val plan = engine.parse(cmd, en)
            assertNotNull("Expected clarification plan for '$cmd'", plan)
            assertTrue("Expected clarification intent for '$cmd', got ${plan!!.intent}", plan.intent.startsWith("clarification_"))
            assertTrue("Expected empty toolCalls for incomplete '$cmd'", plan.toolCalls.isEmpty())
        }

        // False-positive substring regressions
        val profilePlan = engine.parse("Edit my profile", en)
        assertTrue(profilePlan == null || profilePlan.toolCalls.none { it.tool == "search_files" })

        val gmailPlan = engine.parse("Please open Gmail", en)
        assertNotNull(gmailPlan)
        assertEquals("open_app", gmailPlan!!.toolCalls.first().tool)

        val readSmsPlan = engine.parse("Read my incoming message", en)
        assertTrue(readSmsPlan == null || readSmsPlan.toolCalls.none { it.tool == "compose_sms" })
    }

    @Test
    fun `compose sms resolves contact name and detects ambiguous contacts`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Robolectric.setupContentProvider(HardeningContactsProvider::class.java, ContactsContract.AUTHORITY)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.mms"
                name = "com.android.mms.ui.ComposeMessageActivity"
            }
        }
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:%2B919876543210"))
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(smsIntent, resolveInfo)

        val smsTool = ComposeSmsTool(app)

        // Exact single contact match -> resolves number & launches composer cleanly
        val matched = smsTool.execute(mapOf("recipient" to "Mom", "body" to "Reached safely"))
        assertEquals(ExecutionStatus.SUCCESS, matched.status)
        assertEquals("+919876543210", matched.data["recipient_address"])

        // Ambiguous multiple contacts -> refuses silently guessing & asks for clarification
        val ambiguous = smsTool.execute(mapOf("recipient" to "Alex", "body" to "Hey"))
        assertEquals(ExecutionStatus.FAILED, ambiguous.status)
        assertTrue(ambiguous.message.contains("Multiple contacts match", ignoreCase = true))
    }

    @Test
    fun `alarm timer confirmation wording and web search missing-browser safety`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val alarmResolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.deskclock"
                name = "com.android.deskclock.AlarmActivity"
            }
        }
        val alarmIntent = Intent("android.intent.action.SET_ALARM")
        val timerIntent = Intent("android.intent.action.SET_TIMER")
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(alarmIntent, alarmResolve)
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(timerIntent, alarmResolve)

        val alarmRes = SetAlarmTool(app).execute(mapOf("hour" to "6", "minute" to "30", "label" to "Gym"))
        assertEquals(ExecutionStatus.SUCCESS, alarmRes.status)
        assertTrue(alarmRes.message.contains("Opened Clock to confirm", ignoreCase = true))

        val timerRes = SetTimerTool(app).execute(mapOf("seconds" to "120"))
        assertEquals(ExecutionStatus.SUCCESS, timerRes.status)
        assertTrue(timerRes.message.contains("Opened Clock to confirm", ignoreCase = true))

        // Simulate validated internet connectivity so WebSearchTool hits browser resolution check
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        // No browser registered for Google search URI -> returns FAILED cleanly without crashing
        val webRes = WebSearchTool(app).execute(mapOf("query" to "Kotlin Coroutines"))
        assertEquals(ExecutionStatus.FAILED, webRes.status)
        assertTrue("Got: ${webRes.message}", webRes.message.contains("No web browser", ignoreCase = true))
    }

    @Test
    fun `preferences developerModeEnabled defaults false toggles cleanly and sanitizes diagnostic strings`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = CommanderPreferences(app)
        assertFalse(prefs.settings.first().developerModeEnabled)

        assertEquals(
            "Speech Recognition service disconnected. Open Voice Settings.",
            sanitizeUiDiagnostics("Speech Recognition service disconnected (Error 11). Open Voice Settings.", developerMode = false)
        )
        assertEquals(
            "Speech Recognition service disconnected (Error 11). Open Voice Settings.",
            sanitizeUiDiagnostics("Speech Recognition service disconnected (Error 11). Open Voice Settings.", developerMode = true)
        )

        prefs.setDeveloperModeEnabled(true)
        assertTrue(prefs.settings.first().developerModeEnabled)
        prefs.setDeveloperModeEnabled(false)
        assertFalse(prefs.settings.first().developerModeEnabled)
    }

    @Test
    fun `model download manager rejects invalid non-model extensions during local import`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Robolectric.setupContentProvider(FakeImportFileProvider::class.java, FakeImportFileProvider.AUTHORITY)
        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val manager = ModelDownloadManager(app, db.downloadedModelDao(), DeviceCompatibilityManager(app))

        val badUri = Uri.parse("content://${FakeImportFileProvider.AUTHORITY}/notes.txt")
        val badRes = manager.importLocalModel(badUri)
        assertTrue(badRes.isFailure)
        assertTrue(badRes.exceptionOrNull()?.message?.contains("Unsupported file type", ignoreCase = true) == true)

        manager.shutdown()
        db.close()
    }
}

class HardeningContactsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = MatrixCursor(cols)
        val pattern = selectionArgs?.firstOrNull()?.replace("%", "").orEmpty().lowercase()
        val rows = listOf(
            "Mom" to "+919876543210",
            "Alex Work" to "+15551112222",
            "Alex Home" to "+15553334444"
        )
        rows.filter { it.first.lowercase().contains(pattern) }.forEach { (name, num) ->
            cursor.addRow(arrayOf(name, num))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/phone_v2"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

class FakeImportFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val name = uri.lastPathSegment ?: "notes.txt"
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(arrayOf(name, 32L))
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = File(context!!.cacheDir, uri.lastPathSegment ?: "notes.txt").apply {
            writeText("not a litert model")
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.test.import"
    }
}
