package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ai.DeviceCompatibilityManager
import com.example.ai.LocalLlmEngine
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.CommandHistoryEntity
import com.example.data.local.CommanderDatabase
import com.example.domain.model.ActionCategory
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PipelineStage
import com.example.tools.PhoneToolsFactory
import com.example.tools.ToolRegistry
import com.example.ui.animations.AnimatedStageIndicator
import com.example.ui.animations.CommanderAiOrb
import com.example.ui.theme.CommanderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: CommanderDatabase
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, CommanderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val llm = LocalLlmEngine(context)
        val screenManager = ScreenUnderstandingManager(context, llm)
        registry = ToolRegistry().apply {
            registerAll(
                PhoneToolsFactory.createAll(
                    context = context,
                    noteDao = db.localNoteDao(),
                    reminderDao = db.localReminderDao(),
                    screenUnderstandingManager = screenManager
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read app name from context`() {
        assertEquals("AI Commander", context.getString(R.string.app_name))
    }

    @Test
    fun `tool registry contains all 20 plus required tools`() {
        val names = registry.registeredNames()
        val required = listOf(
            "open_app", "open_url", "web_search", "browser_navigate",
            "browser_click", "browser_type", "browser_scroll", "get_screen_text",
            "analyze_screenshot", "adjust_brightness", "adjust_volume", "open_settings",
            "create_reminder", "create_calendar_event", "search_contacts",
            "compose_sms", "compose_email", "search_files", "create_note",
            "check_app_installed"
        )
        required.forEach { toolName ->
            assertTrue("Missing required tool: $toolName", toolName in names)
        }
    }

    @Test
    fun `create note and reminder tools persist locally in Room`() = runTest {
        val noteRes = registry.executeTool(
            "create_note",
            mapOf("title" to "Architecture", "content" to "Offline LiteRT-LM verified")
        )
        assertEquals(ExecutionStatus.SUCCESS, noteRes.status)
        val notes = db.localNoteDao().observeAll().first()
        assertEquals(1, notes.size)
        assertEquals("Architecture", notes.first().title)

        val remRes = registry.executeTool(
            "create_reminder",
            mapOf("title" to "Review local model", "delay_minutes" to "30")
        )
        assertEquals(ExecutionStatus.SUCCESS, remRes.status)
        val reminders = db.localReminderDao().observeAll().first()
        assertEquals(1, reminders.size)
        assertEquals("Review local model", reminders.first().title)
    }

    @Test
    fun `room command history search and delete work`() = runTest {
        val id = db.commandHistoryDao().insert(
            CommandHistoryEntity(
                commandText = "Open Bluetooth settings",
                transcription = "Open Bluetooth settings",
                inputLanguage = "EN_IN",
                engineUsed = "DETERMINISTIC",
                aiInterpretation = "Open Android bluetooth settings",
                selectedToolsJson = "[\"open_settings\"]",
                executionPlanJson = "{}",
                resultStatus = ExecutionStatus.SUCCESS.name,
                resultSummary = "Opened bluetooth settings."
            )
        )
        assertTrue(id > 0)
        val matches = db.commandHistoryDao().search("Bluetooth").first()
        assertEquals(1, matches.size)

        db.commandHistoryDao().deleteById(id)
        val afterDelete = db.commandHistoryDao().observeAll().first()
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun `denied permissions return PERMISSION_REQUIRED cleanly without crashing`() = runTest {
        val contactsRes = registry.executeTool("search_contacts", mapOf("query" to "Alex"))
        assertEquals(ExecutionStatus.PERMISSION_REQUIRED, contactsRes.status)

        val brightnessRes = registry.executeTool("adjust_brightness", mapOf("level" to "75"))
        if (android.provider.Settings.System.canWrite(context)) {
            assertEquals(ExecutionStatus.SUCCESS, brightnessRes.status)
            assertEquals("75", brightnessRes.data["brightness_pct"])
        } else {
            assertEquals(ExecutionStatus.PERMISSION_REQUIRED, brightnessRes.status)
            assertTrue(brightnessRes.requiresWriteSettings)
        }

        val clickRes = registry.executeTool("browser_click", mapOf("target" to "Search"))
        assertEquals(ExecutionStatus.PERMISSION_REQUIRED, clickRes.status)
        assertTrue(clickRes.requiresAccessibility)

        val screenRes = registry.executeTool("analyze_screenshot", emptyMap())
        assertEquals(ExecutionStatus.PERMISSION_REQUIRED, screenRes.status)
        assertTrue(screenRes.requiresScreenCapture)
    }

    @Test
    fun `unknown tool returns UNSUPPORTED and missing params return FAILED`() = runTest {
        val unknown = registry.executeTool("execute_arbitrary_shell", emptyMap())
        assertEquals(ExecutionStatus.UNSUPPORTED, unknown.status)

        val invalidNote = registry.executeTool("create_note", emptyMap())
        assertEquals(ExecutionStatus.FAILED, invalidNote.status)
    }

    @Test
    fun `device compatibility check refuses oversized model safely`() {
        val compat = DeviceCompatibilityManager(context)
        val info = compat.inspectDevice()
        assertNotNull(info.tier)
        val check = compat.checkModelCompatibility(minRamMb = 999_999L, minStorageMb = 10L)
        assertFalse(check.isCompatible)
    }

    @Test
    fun `animated orb and pipeline stage render across states`() {
        composeTestRule.setContent {
            CommanderTheme {
                CommanderAiOrb(
                    orbState = OrbState.THINKING,
                    audioLevel = 0.5f,
                    activeCategory = ActionCategory.BROWSER,
                    onClick = {}
                )
                AnimatedStageIndicator(
                    stage = PipelineStage.EXECUTING,
                    category = ActionCategory.SEARCH,
                    activeToolName = "web_search"
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ai_orb_button").assertIsDisplayed()
    }
}
