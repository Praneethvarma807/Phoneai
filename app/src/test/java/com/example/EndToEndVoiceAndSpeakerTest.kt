package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.LocalLlmEngine
import com.example.automation.AutomationEngine
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.AppLanguage
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PipelineStage
import com.example.memory.PersonalMemoryRepository
import com.example.network.ConnectivityMonitor
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.voice.AudioSignalProcessor
import com.example.voice.DualFeatureVad
import com.example.voice.PrimarySpeakerProfile
import com.example.voice.PrimarySpeakerVerifier
import com.example.voice.VoiceOutputManager
import com.example.voice.WakeWordMatcher
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
@Config(sdk = [34])
class EndToEndVoiceAndSpeakerTest {

    private fun registerLauncherApp(app: Application, pkg: String, label: String, activityCls: String) {
        val pm = Shadows.shadowOf(app.packageManager)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = activityCls
                nonLocalizedLabel = label
            }
        }
        @Suppress("DEPRECATION")
        pm.addResolveInfoForIntent(launcherIntent, resolveInfo)

        val pkgLaunch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        @Suppress("DEPRECATION")
        pm.addResolveInfoForIntent(pkgLaunch, resolveInfo)
    }

    private fun registerIntentAction(app: Application, action: String, pkg: String, cls: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = cls
            }
        }
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(Intent(action), resolveInfo)
    }

    private fun setOnlineConnectivity(app: Application, online: Boolean) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (online) {
            val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        } else {
            val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        }
    }

    private fun synthesizeHarmonicVoice(
        sampleRate: Int = 16_000,
        durationSec: Float = 1.2f,
        f0Hz: Double,
        formantGains: DoubleArray
    ): FloatArray {
        val n = (sampleRate * durationSec).toInt()
        val out = FloatArray(n)
        val harmonics = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 11.0, 15.0)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0
            for (hIdx in harmonics.indices) {
                val gain = formantGains.getOrElse(hIdx) { 0.1 }
                sample += gain * sin(2.0 * PI * f0Hz * harmonics[hIdx] * t)
            }
            // Subtle syllable amplitude envelope
            val env = 0.55 + 0.45 * sin(2.0 * PI * 3.2 * t)
            out[i] = (sample * env * 0.22).toFloat().coerceIn(-0.95f, 0.95f)
        }
        return out
    }

    @Test
    fun `WakeWordMatcher strips inline wake phrases across scripts and preserves command`() {
        assertEquals("open Chrome", WakeWordMatcher.stripWakeWordPrefix("Hello Buddy, open Chrome"))
        assertEquals("Open YouTube", WakeWordMatcher.stripWakeWordPrefix("Hey Hello Buddy: Open YouTube"))
        assertEquals("open WhatsApp", WakeWordMatcher.stripWakeWordPrefix("Helo badi open WhatsApp"))
        assertEquals("open youtube", WakeWordMatcher.stripWakeWordPrefix("హలో బడ్డీ open youtube"))
        assertEquals("बैटरी बताओ", WakeWordMatcher.stripWakeWordPrefix("हेलो बड्डी बैटरी बताओ"))
        assertEquals("open settings", WakeWordMatcher.stripWakeWordPrefix("ஹலோ பட்டி open settings"))
        assertEquals("", WakeWordMatcher.stripWakeWordPrefix("Hello Buddy"))
        assertEquals("Open Settings", WakeWordMatcher.stripWakeWordPrefix("Open Settings"))
    }

    @Test
    fun `instant silent app launch executes without chatter duplicates or confirmations`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        registerLauncherApp(app, "com.android.chrome", "Chrome", "com.google.android.apps.chrome.Main")
        registerLauncherApp(app, "com.google.android.youtube", "YouTube", "com.google.android.youtube.HomeActivity")
        registerLauncherApp(app, "com.whatsapp", "WhatsApp", "com.whatsapp.HomeActivity")
        registerIntentAction(app, Settings.ACTION_SETTINGS, "com.android.settings", "com.android.settings.Settings")

        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java).allowMainThreadQueries().build()
        val prefs = CommanderPreferences(app)
        prefs.setVoiceResponseEnabled(true) // Even when TTS is enabled, silent commands stay silent
        val llm = LocalLlmEngine(app)
        val screenMgr = ScreenUnderstandingManager(app, llm)
        val registry = ToolRegistry().apply {
            registerAll(PhoneToolsFactory.createAll(app, db.localNoteDao(), db.localReminderDao(), screenMgr))
        }
        val voiceOut = VoiceOutputManager(app, createTtsEngine = false)
        val orchestrator = CommandOrchestrator(llm, DeterministicCommandEngine(), registry, db.commandHistoryDao(), prefs, voiceOut)

        try {
            // 1. Inline wake-word Chrome launch
            orchestrator.processCommand("Hello Buddy, open Chrome")
            var st = orchestrator.state.value
            assertEquals(ExecutionStatus.SUCCESS, st.finalStatus)
            assertEquals("", st.finalReply)
            assertEquals(OrbState.IDLE, st.orbState)
            assertEquals(PipelineStage.IDLE, st.pipelineStage)
            assertNull(st.pendingConfirmation)

            val chromeIntent = Shadows.shadowOf(app).nextStartedActivity
            assertNotNull(chromeIntent)
            assertEquals("com.android.chrome", chromeIntent.`package` ?: chromeIntent.component?.packageName)

            // 2. Duplicate suppression prevents launching Chrome twice within cooldown window
            orchestrator.processCommand("Open Chrome")
            assertNull("Duplicate 'Open Chrome' inside cooldown should not re-launch activity", Shadows.shadowOf(app).nextStartedActivity)

            // 3. Explicit retry (allowDuplicate = true) executes immediately
            orchestrator.processCommand("Open Chrome", allowDuplicate = true)
            assertNotNull(Shadows.shadowOf(app).nextStartedActivity)

            // 4. YouTube, WhatsApp, and Settings all launch silently without confirmation or chatter
            listOf("Open YouTube", "Open WhatsApp", "Open Settings").forEach { cmd ->
                orchestrator.processCommand(cmd)
                st = orchestrator.state.value
                assertEquals("Failed command: $cmd", ExecutionStatus.SUCCESS, st.finalStatus)
                assertEquals("Expected silent empty reply for: $cmd", "", st.finalReply)
                assertNull(st.pendingConfirmation)
                assertNotNull("Expected activity started for: $cmd", Shadows.shadowOf(app).nextStartedActivity)
            }

            // 5. Unavailable app still surfaces clear error honestly
            orchestrator.processCommand("Open NonExistentAppXYZ")
            st = orchestrator.state.value
            assertEquals(ExecutionStatus.FAILED, st.finalStatus)
            assertTrue(st.finalReply.contains("not installed", ignoreCase = true))
        } finally {
            voiceOut.shutdown()
            screenMgr.close()
            llm.shutdown()
            db.close()
        }
    }

    @Test
    fun `AudioSignalProcessor DualFeatureVad and PrimarySpeakerVerifier separate speaker from TV and fan noise`() = runTest {
        // 1. Stationary low-frequency fan hum should fail speech-band Dual-VAD check
        val fanNoise = FloatArray(16_000) { i ->
            val t = i.toDouble() / 16_000.0
            (0.015 * sin(2.0 * PI * 60.0 * t) + 0.008 * sin(2.0 * PI * 120.0 * t)).toFloat()
        }
        val cleanFan = AudioSignalProcessor.preprocessFloat(fanNoise)
        val fanVad = DualFeatureVad.evaluateFrame(cleanFan.copyOf(400), 16_000, noiseFloorRms = 0.015f, sensitivity = 0.65f)
        assertFalse("Stationary 60Hz fan hum must not trigger speech VAD", fanVad.isSpeech)

        // 2. Enroll Primary Speaker (125 Hz fundamental + warm low-mid formants)
        val ownerSample1 = synthesizeHarmonicVoice(
            f0Hz = 125.0,
            formantGains = doubleArrayOf(1.2, 1.0, 0.9, 0.7, 0.45, 0.25, 0.12, 0.06)
        )
        val ownerSample2 = synthesizeHarmonicVoice(
            f0Hz = 128.0,
            formantGains = doubleArrayOf(1.15, 1.02, 0.88, 0.72, 0.42, 0.24, 0.11, 0.05)
        )
        val enrollRes = PrimarySpeakerVerifier.enrollFromSamples(listOf(ownerSample1))
        assertTrue("Enrollment should succeed on harmonic speech", enrollRes.isSuccess)
        val profile = enrollRes.getOrThrow()

        // JSON round-trip sanity check
        val decodedProfile = PrimarySpeakerProfile.fromJson(profile.toJson())
        assertNotNull(decodedProfile)
        assertEquals(profile.embedding.size, decodedProfile?.embedding?.size)

        // 3. Matching Primary Speaker accepted
        val sameSpeakerRes = PrimarySpeakerVerifier.verify(
            rawPcm = ownerSample2,
            profile = decodedProfile,
            threshold = 0.75f,
            enforcePrimarySpeaker = true
        )
        assertTrue("Primary speaker sample should be accepted: ${sameSpeakerRes.reason}", sameSpeakerRes.accepted)
        assertTrue(sameSpeakerRes.similarityScore >= 0.75f)

        // 4. Different speaker / high-pitched TV dialogue rejected cleanly
        val tvSpeakerSample = synthesizeHarmonicVoice(
            f0Hz = 275.0,
            formantGains = doubleArrayOf(0.25, 0.35, 0.45, 0.9, 1.2, 1.15, 0.95, 0.85)
        )
        val otherSpeakerRes = PrimarySpeakerVerifier.verify(
            rawPcm = tvSpeakerSample,
            profile = decodedProfile,
            threshold = 0.75f,
            enforcePrimarySpeaker = true
        )
        assertFalse("TV/other speaker sample should be rejected: ${otherSpeakerRes.reason}", otherSpeakerRes.accepted)

        // 5. DataStore persistence verification
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = CommanderPreferences(app)
        prefs.savePrimarySpeakerProfile(profile.toJson(), profile.sampleCount)
        prefs.setPrimarySpeakerOnlyEnabled(true)
        assertTrue(prefs.settings.first().hasPrimarySpeakerProfile)
        assertTrue(prefs.settings.first().primarySpeakerOnlyEnabled)
        prefs.clearPrimarySpeakerProfile()
        assertFalse(prefs.settings.first().hasPrimarySpeakerProfile)
        assertFalse(prefs.settings.first().primarySpeakerOnlyEnabled)
    }

    @Test
    fun `multilingual and requested example commands parse and execute cleanly end to end`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        registerIntentAction(app, Intent.ACTION_MAIN, "com.android.launcher3", "com.android.launcher3.Launcher")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.launcher3"
                    name = "com.android.launcher3.Launcher"
                }
            }
        )
        val pm = Shadows.shadowOf(app.packageManager)
        val chromeComponent = ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")
        @Suppress("DEPRECATION")
        pm.addActivityIfNotPresent(chromeComponent)
        @Suppress("DEPRECATION")
        pm.addIntentFilterForActivity(
            chromeComponent,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            }
        )
        registerLauncherApp(app, "com.google.android.youtube", "YouTube", "com.google.android.youtube.HomeActivity")
        registerIntentAction(app, Settings.ACTION_BLUETOOTH_SETTINGS, "com.android.settings", "com.android.settings.BluetoothSettings")

        val db = Room.inMemoryDatabaseBuilder(app, CommanderDatabase::class.java).allowMainThreadQueries().build()
        val prefs = CommanderPreferences(app)
        prefs.setVoiceResponseEnabled(false)
        val llm = LocalLlmEngine(app)
        val screenMgr = ScreenUnderstandingManager(app, llm)
        val connectivity = ConnectivityMonitor(app)
        val registry = ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = app,
                    noteDao = db.localNoteDao(),
                    reminderDao = db.localReminderDao(),
                    screenUnderstandingManager = screenMgr,
                    connectivityMonitor = connectivity
                )
            )
        }
        val voiceOut = VoiceOutputManager(app, createTtsEngine = false)
        val orchestrator = CommandOrchestrator(
            llm,
            DeterministicCommandEngine(),
            registry,
            db.commandHistoryDao(),
            prefs,
            voiceOut,
            connectivityMonitor = connectivity
        )

        try {
            setOnlineConnectivity(app, true)

            // Search Google
            orchestrator.processCommand("Search Google for Java interview questions")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            val searchIntent = Shadows.shadowOf(app).nextStartedActivity
            assertTrue(searchIntent?.dataString?.contains("Java+interview+questions") == true)

            // YouTube search EN + Telugu
            orchestrator.processCommand("Open YouTube and search Telugu songs")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            val ytIntent = Shadows.shadowOf(app).nextStartedActivity
            assertTrue(ytIntent?.dataString?.contains("Telugu+songs") == true)

            val det = DeterministicCommandEngine()
            val tePlan = det.parse("యూట్యూబ్‌లో తెలుగు పాటలు వెతుకు")
            assertEquals("youtube_search", tePlan?.intent)
            assertEquals(AppLanguage.TELUGU.shortBadge, tePlan?.detectedLanguage)

            // Weather online succeeds with Google query; offline fails honestly
            orchestrator.processCommand("Tell me today's weather")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            Shadows.shadowOf(app).nextStartedActivity

            prefs.setOfflineStrictMode(true)
            orchestrator.processCommand("Tell me today's weather", allowDuplicate = true)
            assertEquals(ExecutionStatus.FAILED, orchestrator.state.value.finalStatus)
            prefs.setOfflineStrictMode(false)

            // Navigate to Home screen (silent)
            orchestrator.processCommand("Navigate to home screen")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            assertEquals("", orchestrator.state.value.finalReply)

            // Take a screenshot -> cleanly requests Accessibility permission when disabled
            orchestrator.processCommand("Take a screenshot")
            assertEquals(ExecutionStatus.PERMISSION_REQUIRED, orchestrator.state.value.finalStatus)
            assertTrue(orchestrator.state.value.stepResults.any { it.requiresAccessibility })

            // Bluetooth settings shortcut (silent)
            orchestrator.processCommand("Turn on Bluetooth")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            val btIntent = Shadows.shadowOf(app).nextStartedActivity
            assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, btIntent?.action)

            // Volume & Notifications
            orchestrator.processCommand("Increase system volume")
            assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)

            orchestrator.processCommand("Read my notifications")
            assertEquals(ExecutionStatus.PERMISSION_REQUIRED, orchestrator.state.value.finalStatus)

            // Brightness checks WRITE_SETTINGS permission or adjusts & verifies readback
            orchestrator.processCommand("Decrease brightness")
            if (Settings.System.canWrite(app)) {
                assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)
            } else {
                assertEquals(ExecutionStatus.PERMISSION_REQUIRED, orchestrator.state.value.finalStatus)
            }
        } finally {
            connectivity.shutdown()
            voiceOut.shutdown()
            screenMgr.close()
            llm.shutdown()
            db.close()
        }
    }
}
