package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PatternMatcher
import android.provider.ContactsContract
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.example.automation.ScreenCaptureService
import com.example.domain.model.ExecutionStatus
import com.example.tools.SendWhatsAppDraftTool
import com.example.voice.SpeechVerificationOutcome
import com.example.voice.VerifierStepDecision
import com.example.voice.WakeWordForegroundService
import com.example.voice.WakeWordLoopController
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1CriticalFixesTest {

    // -------------------------------------------------------------------------
    // Fix 1: ScreenCaptureService backward compatibility, Parcelable, Bitmap
    // -------------------------------------------------------------------------

    @Test
    @Config(sdk = [28])
    fun `screen capture metrics resolve cleanly on API 28 without WindowMetrics crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = ScreenCaptureService.resolveCaptureMetrics(context)
        assertTrue(metrics.width > 0)
        assertTrue(metrics.height > 0)
        assertTrue(metrics.densityDpi >= 120)
    }

    @Test
    @Config(sdk = [36])
    fun `screen capture metrics resolve cleanly on API 36 via WindowMetrics`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metrics = ScreenCaptureService.resolveCaptureMetrics(context)
        assertTrue(metrics.width > 0)
        assertTrue(metrics.height > 0)
        assertTrue(metrics.densityDpi >= 120)
    }

    @Test
    @Config(sdk = [28])
    fun `extractProjectionData retrieves nested Intent safely on API 28`() {
        val inner = Intent("android.media.projection.MEDIA_PROJECTION_TOKEN").apply {
            putExtra("token_id", 42)
        }
        val outer = Intent().apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, inner)
        }
        val extracted = ScreenCaptureService.extractProjectionData(outer)
        assertNotNull(extracted)
        assertEquals(42, extracted?.getIntExtra("token_id", -1))
        assertNull(ScreenCaptureService.extractProjectionData(Intent()))
    }

    @Test
    @Config(sdk = [36])
    fun `extractProjectionData retrieves nested Intent safely on API 36`() {
        val inner = Intent("android.media.projection.MEDIA_PROJECTION_TOKEN").apply {
            putExtra("token_id", 99)
        }
        val outer = Intent().apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, inner)
        }
        val extracted = ScreenCaptureService.extractProjectionData(outer)
        assertNotNull(extracted)
        assertEquals(99, extracted?.getIntExtra("token_id", -1))
    }

    @Test
    fun `copyAndCropPlaneBitmap handles padded rowStride cleanly without invalid bitmap`() {
        val width = 4
        val height = 3
        val pixelStride = 4
        val rowPaddingBytes = 8 // 2 padding pixels per row
        val rowStride = width * pixelStride + rowPaddingBytes
        val buffer = ByteBuffer.allocate(rowStride * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // ARGB_8888 raw pixel bytes: R, G, B, A
                buffer.put((x * 30 + 10).toByte())
                buffer.put((y * 40 + 20).toByte())
                buffer.put(0x7F.toByte())
                buffer.put(0xFF.toByte())
            }
            repeat(rowPaddingBytes) { buffer.put(0) }
        }

        val cropped = ScreenCaptureService.copyAndCropPlaneBitmap(
            buffer = buffer,
            width = width,
            height = height,
            pixelStride = pixelStride,
            rowStride = rowStride
        )
        assertNotNull(cropped)
        assertEquals(width, cropped!!.width)
        assertEquals(height, cropped.height)
        assertFalse(cropped.isRecycled)
        cropped.recycle()
    }

    @Test
    fun `copyAndCropPlaneBitmap handles unpadded buffer and invalid parameters gracefully`() {
        val width = 4
        val height = 2
        val pixelStride = 4
        val rowStride = width * pixelStride
        val buffer = ByteBuffer.allocate(rowStride * height)
        val unpadded = ScreenCaptureService.copyAndCropPlaneBitmap(buffer, width, height, pixelStride, rowStride)
        assertNotNull(unpadded)
        assertEquals(width, unpadded!!.width)
        unpadded.recycle()

        val invalid = ScreenCaptureService.copyAndCropPlaneBitmap(ByteBuffer.allocate(4), width, height, 0, rowStride)
        assertNull(invalid)

        val underflow = ScreenCaptureService.copyAndCropPlaneBitmap(ByteBuffer.allocate(4), width, height, pixelStride, rowStride)
        assertNull(underflow)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `screen capture service returns START_NOT_STICKY and resolves pendingCapture on canceled grant`() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        val service = controller.get()
        val deferred = CompletableDeferred<Bitmap?>()
        ScreenCaptureService.pendingCapture = deferred

        val result = service.onStartCommand(
            Intent().putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
            0,
            1
        )
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(deferred.isCompleted)
        assertNull(deferred.getCompleted())
        controller.destroy()
    }

    // -------------------------------------------------------------------------
    // Fix 2: WakeWordForegroundService lifecycle, cooldown, FGS & backoff
    // -------------------------------------------------------------------------

    @Test
    fun `wake word service returns START_NOT_STICKY for null intent and denied mic permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        val controller = Robolectric.buildService(WakeWordForegroundService::class.java).create()
        val service = controller.get()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        val startResult = service.onStartCommand(
            Intent(WakeWordForegroundService.ACTION_START),
            0,
            2
        )
        assertEquals(Service.START_NOT_STICKY, startResult)
        assertFalse(WakeWordForegroundService.isRunning.value)
        controller.destroy()
    }

    @Test
    fun `wake word loop controller persists cooldown across loop restarts and enforces background API 30+ guard`() {
        val api34Controller = WakeWordLoopController(sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE })
        api34Controller.recordKwsDetection(nowMs = 1_000L)
        assertTrue(api34Controller.isInCooldown(2_000L))
        assertFalse(api34Controller.isInCooldown(5_000L))

        // Background SpeechRecognizer must be blocked on Android 11+ (API 30+)
        assertFalse(api34Controller.canUseSpeechVerifier(nowMs = 10_000L, isAppInForeground = false))
        assertTrue(api34Controller.canUseSpeechVerifier(nowMs = 10_000L, isAppInForeground = true))

        // Older API 29 permits recognizer regardless of foreground flag
        val api29Controller = WakeWordLoopController(sdkIntProvider = { Build.VERSION_CODES.Q })
        assertTrue(api29Controller.canUseSpeechVerifier(nowMs = 10_000L, isAppInForeground = false))
    }

    @Test
    fun `wake word loop controller triggers exponential backoff and circuit breaker after repeated errors`() {
        val controller = WakeWordLoopController(sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE })

        val d1 = controller.handleSpeechOutcome(10_000L, SpeechVerificationOutcome.ServiceFailure(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, "Error 11"))
        assertTrue(d1 is VerifierStepDecision.ContinueCooldown)
        assertEquals(14_000L, controller.cooldownUntilMs)

        val d2 = controller.handleSpeechOutcome(20_000L, SpeechVerificationOutcome.ServiceFailure(SpeechRecognizer.ERROR_CLIENT, "Client"))
        assertTrue(d2 is VerifierStepDecision.ContinueCooldown)
        assertEquals(28_000L, controller.cooldownUntilMs)

        val d3 = controller.handleSpeechOutcome(30_000L, SpeechVerificationOutcome.ServiceFailure(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, "Error 11"))
        assertTrue(d3 is VerifierStepDecision.SuspendedBackoff)
        assertEquals(90_000L, controller.speechVerifierSuspendedUntilMs)
        assertFalse(controller.canUseSpeechVerifier(nowMs = 50_000L, isAppInForeground = true))

        val fatal = controller.handleSpeechOutcome(100_000L, SpeechVerificationOutcome.ServiceFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "Mic denied"))
        assertTrue(fatal is VerifierStepDecision.FatalPermissionStop)

        // Benign NoMatch resets error streak
        controller.handleSpeechOutcome(105_000L, SpeechVerificationOutcome.NoMatch)
        assertEquals(0, controller.consecutiveSpeechErrors)
    }

    @Test
    fun `wake word loop controller aborts after 5 consecutive AudioRecord init failures`() {
        val controller = WakeWordLoopController()
        repeat(4) {
            assertFalse(controller.recordAudioInitFailure())
        }
        assertTrue(controller.recordAudioInitFailure())
        controller.recordAudioInitSuccess()
        assertFalse(controller.recordAudioInitFailure())
    }

    // -------------------------------------------------------------------------
    // Fix 3: WhatsApp package visibility & recipient handling
    // -------------------------------------------------------------------------

    @Test
    fun `whatsapp tool fails clearly when neither WhatsApp nor WhatsApp Business is installed`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val tool = SendWhatsAppDraftTool(context)
        val res = tool.execute(mapOf("recipient" to "+919876543210", "message" to "Hello"))
        assertEquals(ExecutionStatus.FAILED, res.status)
        assertTrue(res.message.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `whatsapp tool launches wa me direct chat for numeric recipient and falls back to w4b`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val spm = Shadows.shadowOf(app.packageManager)

        val waActivity = ComponentName("com.whatsapp.w4b", "com.whatsapp.HomeActivity")
        spm.addActivityIfNotPresent(waActivity)
        spm.addIntentFilterForActivity(
            waActivity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
                addDataAuthority("wa.me", null)
                addDataPath("/.*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            }
        )
        spm.addIntentFilterForActivity(
            waActivity,
            IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            }
        )

        val tool = SendWhatsAppDraftTool(app)
        val res = tool.execute(mapOf("recipient" to "+91 98765 43210", "message" to "Running late"))
        assertEquals(ExecutionStatus.SUCCESS, res.status)
        assertEquals("com.whatsapp.w4b", res.data["package"])
        assertEquals("direct_chat", res.data["mode"])
        assertEquals("919876543210", res.data["recipient_digits"])

        val launched = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(launched)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("com.whatsapp.w4b", launched.`package`)
        assertTrue(launched.data.toString().startsWith("https://wa.me/919876543210"))
    }

    @Test
    fun `whatsapp tool resolves named contact when READ_CONTACTS granted else uses share composer`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val spm = Shadows.shadowOf(app.packageManager)
        val waActivity = ComponentName("com.whatsapp", "com.whatsapp.Conversation")
        spm.addActivityIfNotPresent(waActivity)
        spm.addIntentFilterForActivity(
            waActivity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
                addDataAuthority("wa.me", null)
                addDataPath("/.*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            }
        )
        spm.addIntentFilterForActivity(
            waActivity,
            IntentFilter(Intent.ACTION_SEND).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType("text/plain")
            }
        )

        val tool = SendWhatsAppDraftTool(app)

        // Without READ_CONTACTS -> opens share composer cleanly with guidance
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        val shareRes = tool.execute(mapOf("recipient" to "Alex", "message" to "Hi Alex"))
        assertEquals(ExecutionStatus.SUCCESS, shareRes.status)
        assertEquals("share_composer", shareRes.data["mode"])
        val shareIntent = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertEquals("Hi Alex", shareIntent.getStringExtra(Intent.EXTRA_TEXT))

        // With READ_CONTACTS + mock contacts provider -> direct wa.me chat
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        val directRes = tool.execute(mapOf("recipient" to "Alex", "message" to "Hi Alex"))
        assertEquals(ExecutionStatus.SUCCESS, directRes.status)
        assertEquals("direct_chat", directRes.data["mode"])
        assertEquals("15550109988", directRes.data["recipient_digits"])
    }
}

class FakeContactsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
        )
        val needle = selectionArgs?.firstOrNull()?.replace("%", "")?.lowercase().orEmpty()
        if (needle.isEmpty() || "alex".contains(needle)) {
            cursor.addRow(arrayOf("Alex Mercer", "+1 (555) 010-9988"))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
