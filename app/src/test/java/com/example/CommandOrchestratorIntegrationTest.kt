package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.CommandOrchestrator
import com.example.ai.DeterministicCommandEngine
import com.example.ai.LocalLlmEngine
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommanderDatabase
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.AppLanguage
import com.example.domain.model.ExecutionStatus
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.voice.VoiceOutputManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommandOrchestratorIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: CommanderDatabase
    private lateinit var preferences: CommanderPreferences
    private lateinit var voiceOutput: VoiceOutputManager
    private lateinit var orchestrator: CommandOrchestrator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = CommanderPreferences(context)
        val llm = LocalLlmEngine(context)
        val screenManager = ScreenUnderstandingManager(context, llm)
        val registry = ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = context,
                    noteDao = db.localNoteDao(),
                    reminderDao = db.localReminderDao(),
                    screenUnderstandingManager = screenManager
                )
            )
        }
        voiceOutput = VoiceOutputManager(context)
        orchestrator = CommandOrchestrator(
            localLlmEngine = llm,
            deterministicEngine = DeterministicCommandEngine(),
            toolRegistry = registry,
            historyDao = db.commandHistoryDao(),
            preferences = preferences,
            voiceOutput = voiceOutput
        )
    }

    @After
    fun tearDown() {
        voiceOutput.shutdown()
        db.close()
    }

    @Test
    fun `end to end create note persists note and records history entry`() = runTest {
        preferences.setVoiceResponseEnabled(false)

        orchestrator.processCommand("Create a note titled GroceryList content Milk Eggs")

        val state = orchestrator.state.value
        assertEquals(ExecutionStatus.SUCCESS, state.finalStatus)
        assertEquals("EN", state.detectedLanguageBadge)

        val notes = db.localNoteDao().observeAll().first()
        assertEquals(1, notes.size)

        val history = db.commandHistoryDao().observeAll().first()
        assertEquals(1, history.size)
        assertEquals(ExecutionStatus.SUCCESS.name, history.first().resultStatus)
    }

    @Test
    fun `sensitive messaging action waits for confirmation and cancel updates history cleanly`() = runTest {
        preferences.setVoiceResponseEnabled(false)

        orchestrator.processCommand("Draft SMS to +15551234567 saying Arriving soon")

        val pendingState = orchestrator.state.value
        assertEquals(ExecutionStatus.CONFIRMATION_REQUIRED, pendingState.finalStatus)
        assertNotNull(pendingState.pendingConfirmation)

        orchestrator.cancelPendingAction()

        val afterCancel = orchestrator.state.value
        assertNull(afterCancel.pendingConfirmation)
        assertEquals(ExecutionStatus.CANCELLED, afterCancel.finalStatus)

        val history = db.commandHistoryDao().observeAll().first()
        assertTrue(history.any { it.resultStatus == ExecutionStatus.CANCELLED.name })
    }

    @Test
    fun `explicit language switch updates active conversation language and persists across turns`() = runTest {
        preferences.setVoiceResponseEnabled(false)

        orchestrator.processCommand("Reply in Telugu")
        assertEquals(AppLanguage.TELUGU, orchestrator.state.value.activeConversationLanguage)
        assertEquals(ExecutionStatus.SUCCESS, orchestrator.state.value.finalStatus)

        orchestrator.processCommand("నోట్ రాయి కంటెంట్ టెస్ట్")
        assertEquals(AppLanguage.TELUGU, orchestrator.state.value.activeConversationLanguage)
    }

    @Test
    fun `unsupported script returns UNSUPPORTED without crashing`() = runTest {
        preferences.setVoiceResponseEnabled(false)

        orchestrator.processCommand("こんにちは")

        val state = orchestrator.state.value
        assertEquals(ExecutionStatus.UNSUPPORTED, state.finalStatus)
        assertEquals(AppLanguage.UNSUPPORTED.shortBadge, state.detectedLanguageBadge)
    }

    @Test
    fun `offline web search reports clean network failure`() = runTest {
        preferences.setVoiceResponseEnabled(false)

        orchestrator.processCommand("Search web for Android offline AI")

        val state = orchestrator.state.value
        assertEquals(ExecutionStatus.FAILED, state.finalStatus)
        assertTrue(state.finalReply.isNotBlank())
    }
}
