package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.AppLanguage
import com.example.domain.model.LanguageSupportStatus
import com.example.domain.model.RecognizerBackendMode
import com.example.domain.model.SpeechLanguageOption
import com.example.voice.SpeechRecognizerFactory
import com.example.voice.SpeechRecognizerHandle
import com.example.voice.VoiceRecognitionManager
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRecognitionManagerTest {

    private class FakeRecognizerHandle : SpeechRecognizerHandle {
        var listener: RecognitionListener? = null
        var startedIntents = mutableListOf<Intent>()
        var destroyed = false
        var stopped = false
        var canceled = false
        var supportHandler: ((Intent, Executor, RecognitionSupportCallback) -> Unit)? = null
        var startBehavior: ((FakeRecognizerHandle, Intent) -> Unit)? = null

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(intent: Intent) {
            startedIntents += intent
            startBehavior?.invoke(this, intent)
        }

        override fun stopListening() {
            stopped = true
        }

        override fun cancel() {
            canceled = true
        }

        override fun destroy() {
            destroyed = true
        }

        override fun checkRecognitionSupport(
            intent: Intent,
            executor: Executor,
            callback: RecognitionSupportCallback
        ) {
            supportHandler?.invoke(intent, executor, callback)
                ?: executor.execute { callback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) }
        }

        override fun triggerModelDownload(
            intent: Intent,
            executor: Executor,
            listener: ModelDownloadListener?
        ): Boolean = true
    }

    private class FakeSpeechFactory(
        var recognitionAvailable: Boolean = true,
        var onDeviceAvailable: Boolean = true,
        var recordAudioGranted: Boolean = true,
        var hasInternet: Boolean = true,
        var discovered: List<ComponentName> = listOf(
            ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
        )
    ) : SpeechRecognizerFactory {
        val createdOnDevice = mutableListOf<FakeRecognizerHandle>()
        val createdDefault = mutableListOf<FakeRecognizerHandle>()
        val createdComponent = mutableListOf<FakeRecognizerHandle>()
        var nextOnDevice: () -> FakeRecognizerHandle? = { FakeRecognizerHandle() }
        var nextDefault: () -> FakeRecognizerHandle? = { FakeRecognizerHandle() }
        var nextComponent: () -> FakeRecognizerHandle? = { FakeRecognizerHandle() }
        var supportChecker: FakeRecognizerHandle? = FakeRecognizerHandle()

        override fun isRecognitionAvailable(context: Context) = recognitionAvailable
        override fun isOnDeviceRecognitionAvailable(context: Context) = onDeviceAvailable
        override fun discoverServices(context: Context) = discovered
        override fun hasInternetConnection(context: Context) = hasInternet
        override fun isRecordAudioGranted(context: Context) = recordAudioGranted

        override fun createOnDeviceRecognizer(context: Context): SpeechRecognizerHandle? {
            if (supportChecker != null && createdOnDevice.isEmpty() && nextOnDeviceBehaviorIsSupportProbe) {
                nextOnDeviceBehaviorIsSupportProbe = false
                return supportChecker
            }
            return nextOnDevice()?.also { createdOnDevice += it }
        }

        var nextOnDeviceBehaviorIsSupportProbe = false

        override fun createDefaultRecognizer(context: Context): SpeechRecognizerHandle? =
            nextDefault()?.also { createdDefault += it }

        override fun createComponentRecognizer(
            context: Context,
            componentName: ComponentName
        ): SpeechRecognizerHandle? =
            nextComponent()?.also { createdComponent += it }
    }

    private fun drainMainLooperFor(ms: Long = 500L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Test
    fun `discovers RecognitionService from PackageManager safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.google.android.tts"
                name = "com.example.FakeGoogleSpeechService"
            }
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolveInfo)

        val manager = VoiceRecognitionManager(context)
        drainMainLooperFor(50)
        val caps = manager.checkCapabilities()
        assertTrue(caps.discoveredServices.any { it.contains("com.google.android.tts") })
        assertEquals("com.google.android.tts", caps.servicePackageName)
        manager.shutdown()
    }

    @Test
    fun `handles Error Code 11 on on-device recognizer by falling back once to platform recognizer without infinite loop`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(recognitionAvailable = true, onDeviceAvailable = true, hasInternet = true)

        val onDeviceHandle = FakeRecognizerHandle().apply {
            startBehavior = { h, _ ->
                h.listener?.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
            }
        }
        val platformHandle = FakeRecognizerHandle().apply {
            startBehavior = { h, _ ->
                h.listener?.onReadyForSpeech(Bundle())
                h.listener?.onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("open chrome"))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.95f))
                })
            }
        }
        factory.nextOnDevice = { onDeviceHandle }
        factory.nextDefault = { platformHandle }

        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(100)

        var resultText: String? = null
        var errorText: String? = null
        manager.startListening(
            language = SpeechLanguageOption.TE_IN,
            onResult = { resultText = it },
            onError = { errorText = it }
        )
        drainMainLooperFor(800)

        assertNull(errorText)
        assertEquals("open chrome", resultText)
        assertEquals(RecognizerBackendMode.SYSTEM_DEFAULT, manager.state.value.backendMode)
        assertTrue(onDeviceHandle.destroyed)
        assertFalse(platformHandle.startedIntents.first().getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
        manager.shutdown()
    }

    @Test
    fun `stops cleanly after bounded fallback when all backends return Error Code 11`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(recognitionAvailable = true, onDeviceAvailable = true)

        var totalAttempts = 0
        factory.nextOnDevice = {
            FakeRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    totalAttempts++
                    h.listener?.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                }
            }
        }
        factory.nextDefault = {
            FakeRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    totalAttempts++
                    h.listener?.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                }
            }
        }
        factory.nextComponent = {
            FakeRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    totalAttempts++
                    h.listener?.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
                }
            }
        }

        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(100)

        var errorMsg: String? = null
        manager.startListening(
            language = SpeechLanguageOption.AUTO,
            onResult = {},
            onError = { errorMsg = it }
        )
        drainMainLooperFor(2500)

        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Error 11", ignoreCase = true))
        assertEquals(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, manager.state.value.lastErrorCode)
        assertTrue(manager.state.value.canRetry)
        assertFalse(manager.state.value.isListening)
        // Bounded to MAX_ATTEMPT_COUNT (no infinite loop!)
        assertTrue("Expected <= 3 attempts, got $totalAttempts", totalAttempts in 2..3)
        manager.shutdown()
    }

    @Test
    fun `handles Error Code 14 ERROR_CANNOT_CHECK_SUPPORT honestly without false offline claims`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(recognitionAvailable = true, onDeviceAvailable = true, hasInternet = true)
        factory.nextOnDeviceBehaviorIsSupportProbe = true
        factory.supportChecker = FakeRecognizerHandle().apply {
            supportHandler = { _, executor, cb ->
                executor.execute { cb.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) }
            }
        }

        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(200)

        val status = manager.state.value.languageSupportMap["te-IN"]
        assertEquals(LanguageSupportStatus.UNVERIFIED_ERROR_14, status)
        assertTrue(manager.state.value.missingPackWarning?.contains("Error 14") == true)
        manager.shutdown()
    }

    @Test
    fun `verifies English US, English IN, Telugu, Hindi, Tamil, Kannada, Malayalam support accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(recognitionAvailable = true, onDeviceAvailable = true)
        val manager = VoiceRecognitionManager(context, factory)

        // All 8 SpeechLanguageOptions resolve proper BCP-47 tags
        assertEquals("en-IN", manager.resolveLocaleTag(SpeechLanguageOption.EN_IN))
        assertEquals("en-US", manager.resolveLocaleTag(SpeechLanguageOption.EN_US))
        assertEquals("te-IN", manager.resolveLocaleTag(SpeechLanguageOption.TE_IN))
        assertEquals("hi-IN", manager.resolveLocaleTag(SpeechLanguageOption.HI_IN))
        assertEquals("ta-IN", manager.resolveLocaleTag(SpeechLanguageOption.TA_IN))
        assertEquals("kn-IN", manager.resolveLocaleTag(SpeechLanguageOption.KN_IN))
        assertEquals("ml-IN", manager.resolveLocaleTag(SpeechLanguageOption.ML_IN))

        val intent = manager.buildRecognizeIntent(SpeechLanguageOption.AUTO, "te-IN", preferOffline = false)
        val allowed = intent.getStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES)
        assertNotNull(allowed)
        assertTrue(allowed!!.containsAll(listOf("en-IN", "en-US", "te-IN", "hi-IN", "ta-IN", "kn-IN", "ml-IN")))
        manager.shutdown()
    }

    @Test
    fun `blocks recognition cleanly when RECORD_AUDIO permission is denied`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(recordAudioGranted = false)
        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(50)

        var err: String? = null
        manager.startListening(SpeechLanguageOption.EN_US, onResult = {}, onError = { err = it })
        drainMainLooperFor(100)

        assertNotNull(err)
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, manager.state.value.lastErrorCode)
        assertTrue(factory.createdOnDevice.none { it.startedIntents.isNotEmpty() })
        assertTrue(factory.createdDefault.none { it.startedIntents.isNotEmpty() })
        manager.shutdown()
    }

    @Test
    fun `prevents simultaneous overlapping sessions and ignores stale callbacks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(onDeviceAvailable = false)
        val firstHandle = FakeRecognizerHandle()
        val secondHandle = FakeRecognizerHandle()
        var count = 0
        factory.nextDefault = {
            if (count++ == 0) firstHandle else secondHandle
        }

        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(50)

        var firstCallbackCalled = false
        var secondResult: String? = null

        manager.startListening(
            SpeechLanguageOption.TE_IN,
            onResult = { firstCallbackCalled = true },
            onError = { firstCallbackCalled = true }
        )
        drainMainLooperFor(50)

        // Start a second session immediately
        manager.startListening(
            SpeechLanguageOption.EN_IN,
            onResult = { secondResult = it },
            onError = {}
        )
        drainMainLooperFor(250)

        // Late callback arriving from stale first recognizer must be ignored
        firstHandle.listener?.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("stale command"))
        })
        secondHandle.listener?.onResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("నమస్కారం search tutorials"))
        })
        drainMainLooperFor(50)

        assertFalse(firstCallbackCalled)
        assertEquals("నమస్కారం search tutorials", secondResult)
        assertTrue(firstHandle.destroyed)
        manager.shutdown()
    }

    @Test
    fun `handles Error 10 rate limit without auto retry spam and Error 8 busy with bounded 1-shot retry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(onDeviceAvailable = false)

        // Busy twice -> should try 2 times total and stop cleanly
        var busyCount = 0
        factory.nextDefault = {
            FakeRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    busyCount++
                    h.listener?.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                }
            }
        }
        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(50)

        var errMsg: String? = null
        manager.startListening(SpeechLanguageOption.EN_IN, onResult = {}, onError = { errMsg = it })
        drainMainLooperFor(1000)

        assertEquals(2, busyCount)
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, manager.state.value.lastErrorCode)
        assertNotNull(errMsg)

        // Error 10 -> zero retries, sets cooldown
        var tooManyCalls = 0
        factory.nextDefault = {
            FakeRecognizerHandle().apply {
                startBehavior = { h, _ ->
                    tooManyCalls++
                    h.listener?.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
                }
            }
        }
        manager.startListening(SpeechLanguageOption.EN_IN, onResult = {}, onError = { errMsg = it })
        drainMainLooperFor(500)

        assertEquals(1, tooManyCalls)
        assertTrue(manager.isRateLimited())
        assertEquals(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, manager.state.value.lastErrorCode)
        manager.shutdown()
    }

    @Test
    fun `deduplicates repeated partial transcripts and rescues spoken partial on timeout`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(onDeviceAvailable = false)
        val handle = FakeRecognizerHandle()
        factory.nextDefault = { handle }

        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(50)

        assertEquals("open chrome", manager.deduplicateTranscript("open chrome open chrome"))
        assertEquals("hello buddy", manager.deduplicateTranscript("hello hello buddy"))
        assertEquals(
            "YouTube lo Java search chey",
            manager.pickBestTranscript(
                listOf("YouTube lo Java search", "YouTube lo Java search chey"),
                floatArrayOf(0.60f, 0.91f)
            )
        )

        var rescued: String? = null
        manager.startListening(SpeechLanguageOption.TE_IN, onResult = { rescued = it }, onError = {})
        drainMainLooperFor(50)

        handle.listener?.onReadyForSpeech(Bundle())
        handle.listener?.onLanguageDetection(Bundle().apply {
            putString(SpeechRecognizer.DETECTED_LANGUAGE, "te-IN")
        })
        handle.listener?.onPartialResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("YouTube lo Java"))
        })
        handle.listener?.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        drainMainLooperFor(100)

        assertEquals("YouTube lo Java", rescued)
        assertEquals("te-IN", manager.state.value.detectedSpokenLocale)
        assertEquals(AppLanguage.TELUGU, manager.state.value.detectedAppLanguage)
        manager.shutdown()
    }

    @Test
    fun `reports unavailable backend cleanly when no recognition service exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = FakeSpeechFactory(
            recognitionAvailable = false,
            onDeviceAvailable = false,
            discovered = emptyList()
        )
        val manager = VoiceRecognitionManager(context, factory)
        drainMainLooperFor(50)

        var errMsg: String? = null
        manager.startListening(SpeechLanguageOption.EN_IN, onResult = {}, onError = { errMsg = it })
        drainMainLooperFor(50)

        assertFalse(manager.state.value.isRecognitionAvailable)
        assertEquals(RecognizerBackendMode.UNAVAILABLE, manager.state.value.backendMode)
        assertNotNull(errMsg)
        assertTrue(errMsg!!.contains("Google Speech Services", ignoreCase = true))
        manager.shutdown()
    }
}
