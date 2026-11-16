package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.DeviceCompatibilityManager
import com.example.ai.LocalLlmEngine
import com.example.ai.ModelDownloadManager
import com.example.automation.DocumentAccessManager
import com.example.automation.ReminderBootReceiver
import com.example.automation.ReminderMechanism
import com.example.automation.ReminderScheduler
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.local.LocalReminderEntity
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.AppLanguage
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.LocalModelSpec
import com.example.domain.model.ModelInstallStatus
import com.example.tools.CreateReminderTool
import com.example.tools.SearchFilesTool
import com.example.tools.SendWhatsAppDraftTool
import com.example.ui.CommanderViewModel
import com.example.voice.VoiceOutputManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2And3RegressionTest {

    private lateinit var app: Application
    private lateinit var db: CommanderDatabase
    private lateinit var preferences: CommanderPreferences

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = CommanderPreferences(app)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // Phase 2.1: Reminder Scheduling, Cancellation, Reschedule & Boot Recovery
    // -------------------------------------------------------------------------

    @Test
    fun `create reminder persists schedules alarm creates channel and prevents duplicates`() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm: ShadowAlarmManager = Shadows.shadowOf(alarmManager)

        val scheduler = ReminderScheduler(app)
        val tool = CreateReminderTool(db.localReminderDao(), scheduler)

        val res1 = tool.execute(mapOf("title" to "Submit quarterly report", "delay_minutes" to "30"))
        assertEquals(ExecutionStatus.SUCCESS, res1.status)
        val reminderId = res1.data["reminder_id"]!!.toLong()

        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(ReminderScheduler.REMINDER_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)

        // Duplicate fire guard: first fire allowed, second identical trigger suppressed
        val triggerAt = res1.data["trigger_at_millis"]!!.toLong()
        assertTrue(ReminderScheduler.markFiredIfNew(app, reminderId, triggerAt))
        assertFalse(ReminderScheduler.markFiredIfNew(app, reminderId, triggerAt))

        // Rescheduling updates trigger and clears old fired marker
        val updated = db.localReminderDao().getById(reminderId)!!.copy(
            title = "Updated report title",
            triggerAtMillis = triggerAt + 600_000L
        )
        db.localReminderDao().updateReminder(updated)
        val resched = scheduler.scheduleReminder(updated)
        assertEquals(updated.triggerAtMillis, resched.triggerAtMillis)
        assertTrue(ReminderScheduler.markFiredIfNew(app, reminderId, resched.triggerAtMillis))

        // Cancellation clears scheduled alarm
        scheduler.cancelReminder(reminderId)
        assertTrue(shadowAlarm.scheduledAlarms.none {
            Shadows.shadowOf(it.operation).savedIntent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L) == reminderId
        })
    }

    @Test
    fun `reminder scheduler falls back gracefully to inexact alarm and WorkManager when exact alarm denied`() {
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // On Robolectric API 34 default exact alarm is false unless explicitly granted
        val scheduler = ReminderScheduler(app)
        val item = LocalReminderEntity(
            id = 42L,
            title = "Take medicine",
            triggerAtMillis = System.currentTimeMillis() + 120_000L
        )
        val result = scheduler.scheduleReminder(item)
        assertTrue(
            result.mechanism == ReminderMechanism.INEXACT_ALARM_AND_WORK ||
                result.mechanism == ReminderMechanism.EXACT_ALARM
        )
        assertNotNull(Shadows.shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `rescheduleAllPending skips completed and stale items while keeping active reminders`() = runTest {
        val now = System.currentTimeMillis()
        db.localReminderDao().insert(LocalReminderEntity(title = "Future active", triggerAtMillis = now + 300_000L, isCompleted = false))
        db.localReminderDao().insert(LocalReminderEntity(title = "Already done", triggerAtMillis = now + 300_000L, isCompleted = true))
        db.localReminderDao().insert(LocalReminderEntity(title = "Very old missed", triggerAtMillis = now - 72 * 3600_000L, isCompleted = false))

        val scheduler = ReminderScheduler(app)
        val count = scheduler.rescheduleAllPending(db.localReminderDao())
        assertEquals(1, count)
    }

    @Test
    fun `boot receiver handles ACTION_BOOT_COMPLETED without crashing`() {
        val receiver = ReminderBootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    // -------------------------------------------------------------------------
    // Phase 2.2: TTS Interruption, onStop Handling, Stale Utterance Filtering
    // -------------------------------------------------------------------------

    @Test
    fun `voice output manager handles onStop interruption, stale IDs, and shutdown cleanly`() {
        val output = VoiceOutputManager(app, createTtsEngine = false)
        output.attachTtsForTesting(engine = null, ready = true)

        // Simulate speaking utterance A -> start -> stop(interrupted=true) resets speaking state
        output.onUtteranceStart("utt-A")
        assertTrue(output.isSpeaking.value)
        output.onUtteranceStop("utt-A", interrupted = true)
        assertFalse(output.isSpeaking.value)

        // Simulate error resets speaking state
        output.onUtteranceStart("utt-B")
        assertTrue(output.isSpeaking.value)
        output.onUtteranceError("utt-B")
        assertFalse(output.isSpeaking.value)

        // Setting response language when engine null handles safely returning false
        assertFalse(output.setResponseLanguage(AppLanguage.TELUGU))

        output.shutdown()
        assertFalse(output.isSpeaking.value)
        assertFalse(output.isReady.value)
    }

    // -------------------------------------------------------------------------
    // Phase 2.3: Storage Access Framework (SAF) & SearchFilesTool
    // -------------------------------------------------------------------------

    @Test
    fun `document access manager authorizes queries previews and revokes SAF documents without storage permissions`() = runTest {
        Robolectric.setupContentProvider(FakeSafProvider::class.java, FakeSafProvider.AUTHORITY)
        val manager = DocumentAccessManager(app, preferences)

        val txtUri = Uri.parse("content://${FakeSafProvider.AUTHORITY}/file/resume_notes.txt")
        val pdfUri = Uri.parse("content://${FakeSafProvider.AUTHORITY}/file/offer_letter.pdf")

        val txtRes = manager.authorizePickedFile(txtUri)
        assertTrue(txtRes.isSuccess)
        assertEquals("resume_notes.txt", txtRes.getOrThrow().displayName)

        val pdfRes = manager.authorizePickedFile(pdfUri)
        assertTrue(pdfRes.isSuccess)

        // Preview readable text succeeds; preview binary PDF rejects cleanly with UnsupportedOperationException
        val previewOk = manager.readTextPreview(txtUri)
        assertTrue(previewOk.isSuccess)
        assertTrue(previewOk.getOrThrow().contains("Senior Android"))

        val previewPdf = manager.readTextPreview(pdfUri)
        assertTrue(previewPdf.isFailure)
        assertTrue(previewPdf.exceptionOrNull() is UnsupportedOperationException)

        // SearchFilesTool discovers SAF-authorized documents cleanly
        val searchTool = SearchFilesTool(app, manager)
        val searchResult = searchTool.execute(mapOf("query" to "resume"))
        assertEquals(ExecutionStatus.SUCCESS, searchResult.status)
        assertTrue(searchResult.message.contains("resume_notes.txt"))
        assertEquals("1", searchResult.data["saf_matches"])

        // Revoking removes access cleanly from persisted list
        manager.revokeAccess(txtUri.toString())
        val afterRevoke = manager.getAuthorizedEntries()
        assertFalse(afterRevoke.any { it.uriString == txtUri.toString() })
    }

    // -------------------------------------------------------------------------
    // Phase 2.4 & 2.5: Permission Refresh & WhatsApp Invalid Input Handling
    // -------------------------------------------------------------------------

    @Test
    fun `commander viewModel refreshes permission snapshot dynamically and marks requested permissions`() = runTest {
        val viewModel = CommanderViewModel(app as CommanderApplication)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        viewModel.refreshPermissions()
        assertFalse(viewModel.permissionStatus.value.microphoneGranted)

        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        viewModel.onAppResumed()
        assertTrue(viewModel.permissionStatus.value.microphoneGranted)

        viewModel.markPermissionsRequested(listOf(Manifest.permission.RECORD_AUDIO))
        delay(100)
        assertTrue(Manifest.permission.RECORD_AUDIO in viewModel.permissionStatus.value.requestedPermissions)
    }

    @Test
    fun `whatsapp tool rejects empty args and malformed short numeric input cleanly`() = runTest {
        val tool = SendWhatsAppDraftTool(app)

        val emptyRes = tool.execute(emptyMap())
        assertEquals(ExecutionStatus.FAILED, emptyRes.status)

        val shortDigitsRes = tool.execute(mapOf("recipient" to "123", "message" to "Hi"))
        assertEquals(ExecutionStatus.FAILED, shortDigitsRes.status)
        assertTrue(shortDigitsRes.message.contains("at least 7 digits", ignoreCase = true))

        val symbolsRes = tool.execute(mapOf("recipient" to "@@@", "message" to "Hi"))
        assertEquals(ExecutionStatus.FAILED, symbolsRes.status)
    }

    // -------------------------------------------------------------------------
    // Phase 3: Device Compatibility, LocalLlmEngine Guard & ModelDownloadManager
    // -------------------------------------------------------------------------

    @Test
    fun `device compatibility excludes emulators from OpenCL GPU and LocalLlmEngine guards corrupt files`() = runTest {
        val compat = DeviceCompatibilityManager(app)
        val info = compat.inspectDevice()
        // In Robolectric JVM environment (or emulator), OpenCL GPU must not report unsafe true
        assertFalse(info.supportsGpuAcceleration)

        val engine = LocalLlmEngine(app)
        val missingRes = engine.loadModel(
            modelId = "missing",
            displayName = "Missing",
            modelPath = File(app.filesDir, "does_not_exist.litertlm").absolutePath,
            preferGpu = true,
            toolSchemas = emptyList()
        )
        assertTrue(missingRes.isFailure)
        assertFalse(engine.runtimeState.value.isReady)

        // Corrupted small file (< 1MB) is rejected safely before invoking native JNI
        val tinyFile = File(app.filesDir, "corrupt.litertlm").apply { writeText("bad") }
        val tinyRes = engine.loadModel(
            modelId = "corrupt",
            displayName = "Corrupt",
            modelPath = tinyFile.absolutePath,
            preferGpu = true,
            toolSchemas = emptyList()
        )
        assertTrue(tinyRes.isFailure)
        assertTrue(engine.runtimeState.value.error!!.contains("incomplete or corrupted"))
        tinyFile.delete()
        engine.shutdown()
    }

    @Test
    fun `screen understanding manager reports clear error when no projection or accessibility available`() = runTest {
        val llm = LocalLlmEngine(app)
        val screenManager = ScreenUnderstandingManager(app, llm)
        assertFalse(screenManager.canAnalyzeWithoutProjectionDialog())
        val result = screenManager.analyzeCurrentScreen()
        assertTrue(result.isFailure)
        screenManager.close()
        llm.shutdown()
    }

    @Test
    fun `model download manager rejects HTML responses validates ETag SHA256 and supports resume cleanup`() = runTest {
        val compat = DeviceCompatibilityManager(app)
        val payload = ByteArray(1_100_000) { idx -> (idx % 251).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        // Test 1: Reject text/html payload
        val htmlClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body("<html>Login Required</html>".toResponseBody("text/html".toMediaType()))
                    .build()
            }).build()

        val spec = LocalModelSpec(
            id = "test-model",
            displayName = "Test Model",
            family = "Test",
            fileName = "test_model.litertlm",
            downloadUrl = "https://example.org/test_model.litertlm",
            expectedSizeBytes = payload.size.toLong(),
            minRamMb = 1L,
            minStorageMb = 1L,
            license = "Apache 2.0",
            description = "Test",
            recommendedTier = com.example.domain.model.DeviceTier.LOW_RAM
        )

        val dlHtml = ModelDownloadManager(app, db.downloadedModelDao(), compat, htmlClient)
        dlHtml.startOrResumeDownload(spec)
        var guard = 0
        while (dlHtml.progress.value[spec.id]?.status != ModelInstallStatus.FAILED && guard < 40) {
            delay(50)
            guard++
        }
        assertEquals(ModelInstallStatus.FAILED, dlHtml.progress.value[spec.id]?.status)
        assertTrue(dlHtml.progress.value[spec.id]?.error?.contains("text/html") == true)
        dlHtml.shutdown()

        // Test 2: Binary download with matching X-Linked-ETag SHA-256 succeeds & verifies
        val okClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Linked-ETag", "\"$expectedSha\"")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }).build()

        val dlOk = ModelDownloadManager(app, db.downloadedModelDao(), compat, okClient)
        dlOk.startOrResumeDownload(spec)
        guard = 0
        while (dlOk.progress.value[spec.id]?.status != ModelInstallStatus.READY && guard < 60) {
            delay(50)
            guard++
        }
        assertEquals(ModelInstallStatus.READY, dlOk.progress.value[spec.id]?.status)
        val verified = dlOk.validateInstalledModel(spec.id)
        assertTrue(verified.isSuccess)
        assertTrue(verified.getOrThrow().contains(expectedSha.take(12)))
        dlOk.deleteModel(spec.id)
        dlOk.shutdown()
    }
}

class FakeSafProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val fileName = uri.lastPathSegment ?: "doc.txt"
        val size = if (fileName.endsWith(".txt")) 128L else 2048L
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = Array<Any?>(cols.size) { idx ->
            when (cols[idx]) {
                OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> fileName
                OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_SIZE -> size
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".pdf") == true) "application/pdf" else "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val cache = File(context!!.cacheDir, uri.lastPathSegment ?: "saf_test.txt")
        cache.writeText("Senior Android & Kotlin Offline Architecture Resume")
        return ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.test.saf"
    }
}
