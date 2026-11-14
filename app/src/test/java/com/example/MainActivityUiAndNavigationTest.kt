package com.example

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ui.CommanderViewModel
import com.example.ui.theme.CommanderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityUiAndNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `full app navigation and multilingual dashboard execution render cleanly`() {
        val app = ApplicationProvider.getApplicationContext<CommanderApplication>()
        val viewModel = CommanderViewModel(app)

        composeTestRule.setContent {
            CommanderTheme {
                CommanderAppShell(
                    widthSizeClass = WindowWidthSizeClass.Compact,
                    viewModel = viewModel
                )
            }
        }
        composeTestRule.waitForIdle()

        // Dashboard initial render
        composeTestRule.onNodeWithTag("ai_orb_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("command_input_field").assertIsDisplayed()

        // Execute a mixed command via text field
        composeTestRule.onNodeWithTag("command_input_field")
            .performTextInput("Create note TeluguNote hello")
        composeTestRule.onNodeWithTag("send_command_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 6_000) {
            composeTestRule.onAllNodesWithTag("execution_reply_text").fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodesWithTag("detected_language_pill").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("detected_language_pill"))
        composeTestRule.onNodeWithTag("detected_language_pill").assertIsDisplayed()

        // Switch through all 5 bottom navigation screens
        composeTestRule.onNodeWithTag("nav_models").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Local AI Model Manager").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tools").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Automation & Tool Registry").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_history").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_search_field").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_lazy_column")
            .performScrollToNode(hasTestTag("lang_chip_TE_IN"))
        composeTestRule.onNodeWithTag("lang_chip_TE_IN").assertIsDisplayed()
        composeTestRule.onNodeWithTag("lang_chip_TE_IN").performClick()
        composeTestRule.waitForIdle()

        // Return to Dashboard cleanly
        composeTestRule.onNodeWithTag("nav_dashboard").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("ai_orb_button"))
        composeTestRule.onNodeWithTag("ai_orb_button").assertIsDisplayed()
    }
}
