package com.example

import com.example.ai.DeterministicCommandEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCommandEngineTest {

    private val engine = DeterministicCommandEngine()

    @Test
    fun `test 1 open chrome`() {
        val plan = engine.parse("Open Chrome")
        assertNotNull(plan)
        assertEquals("open_app", plan!!.toolCalls.first().tool)
        assertEquals("Chrome", plan.toolCalls.first().arguments["app_name"])
    }

    @Test
    fun `test 2 open youtube`() {
        val plan = engine.parse("Open YouTube")
        assertNotNull(plan)
        assertEquals("open_app", plan!!.toolCalls.first().tool)
        assertEquals("YouTube", plan.toolCalls.first().arguments["app_name"])
    }

    @Test
    fun `test 3 youtube java tutorials english and telugu-english`() {
        val enPlan = engine.parse("Search YouTube for Java tutorials")
        assertNotNull(enPlan)
        assertEquals("open_url", enPlan!!.toolCalls.first().tool)
        assertTrue(enPlan.toolCalls.first().arguments["url"]!!.contains("Java+tutorials"))

        val tePlan = engine.parse("YouTube lo Java tutorials search chey")
        assertNotNull(tePlan)
        assertEquals("open_url", tePlan!!.toolCalls.first().tool)
        assertTrue(tePlan.toolCalls.first().arguments["url"]!!.contains("Java+tutorials"))
    }

    @Test
    fun `test 4 open bluetooth settings`() {
        val plan = engine.parse("Open Bluetooth settings")
        assertNotNull(plan)
        assertEquals("open_settings", plan!!.toolCalls.first().tool)
        assertEquals("bluetooth", plan.toolCalls.first().arguments["section"])
    }

    @Test
    fun `test 5 adjust brightness`() {
        val plan = engine.parse("Set brightness to 70%")
        assertNotNull(plan)
        assertEquals("adjust_brightness", plan!!.toolCalls.first().tool)
        assertEquals("70", plan.toolCalls.first().arguments["level"])
    }

    @Test
    fun `test 6 create reminder`() {
        val plan = engine.parse("Remind me to submit tax documents")
        assertNotNull(plan)
        assertEquals("create_reminder", plan!!.toolCalls.first().tool)
        assertEquals("submit tax documents", plan.toolCalls.first().arguments["title"])
    }

    @Test
    fun `test 7 draft sms requires confirmation`() {
        val plan = engine.parse("Draft SMS to Alex saying I am on my way")
        assertNotNull(plan)
        assertEquals("compose_sms", plan!!.toolCalls.first().tool)
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun `test 8 draft email requires confirmation`() {
        val plan = engine.parse("Draft email to hr@example.com about interview saying Thank you")
        assertNotNull(plan)
        assertEquals("compose_email", plan!!.toolCalls.first().tool)
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun `test 9 search local files`() {
        val plan = engine.parse("Search files for resume")
        assertNotNull(plan)
        assertEquals("search_files", plan!!.toolCalls.first().tool)
        assertEquals("resume", plan.toolCalls.first().arguments["query"])
    }

    @Test
    fun `test 10 explain screen content`() {
        val plan = engine.parse("Explain my screen")
        assertNotNull(plan)
        assertEquals("analyze_screenshot", plan!!.toolCalls.first().tool)
    }

    @Test
    fun `test 11 multi step naukri search and headline update`() {
        val searchPlan = engine.parse("Open Naukri and search for SAP FICO jobs in Hyderabad")
        assertNotNull(searchPlan)
        assertTrue(searchPlan!!.toolCalls.size >= 2)
        assertEquals("open_url", searchPlan.toolCalls[0].tool)
        assertTrue(searchPlan.toolCalls[0].arguments["url"]!!.contains("naukri.com"))
        assertEquals("get_screen_text", searchPlan.toolCalls[1].tool)

        val updatePlan = engine.parse("Update my Naukri resume headline to Senior SAP FICO Consultant")
        assertNotNull(updatePlan)
        assertTrue(updatePlan!!.requiresConfirmation)
        assertEquals("browser_type", updatePlan.toolCalls.last().tool)
    }

    @Test
    fun `test 12 unrecognized complex query returns null honestly when no llm`() {
        val plan = engine.parse("Philosophically compare quantum gravity theories")
        assertNull(plan)
    }

    @Test
    fun `test 13 multilingual commands in native scripts parse accurately`() {
        val teVolume = engine.parse("వాల్యూమ్ పెంచు")
        assertNotNull(teVolume)
        assertEquals("adjust_volume", teVolume!!.toolCalls.first().tool)
        assertEquals(com.example.domain.model.AppLanguage.TELUGU.shortBadge, teVolume.detectedLanguage)

        val hiSearch = engine.parse("यूट्यूब पर Kotlin tutorials खोजो")
        assertNotNull(hiSearch)
        assertEquals("open_url", hiSearch!!.toolCalls.first().tool)
        assertEquals("${com.example.domain.model.AppLanguage.HINDI.shortBadge} + ${com.example.domain.model.AppLanguage.ENGLISH.shortBadge}", hiSearch.detectedLanguage)

        val taOpen = engine.parse("Chrome திற")
        assertNotNull(taOpen)
        assertEquals("open_app", taOpen!!.toolCalls.first().tool)
        assertEquals("Chrome", taOpen.toolCalls.first().arguments["app_name"])

        val knBrightness = engine.parse("ಬ್ರೈಟ್ನೆಸ್ 70 ಪರ್ಸೆಂಟ್ ಸೆಟ್ ಮಾಡು")
        assertNotNull(knBrightness)
        assertEquals("adjust_brightness", knBrightness!!.toolCalls.first().tool)
        assertEquals("70", knBrightness.toolCalls.first().arguments["level"])

        val mlPlan = engine.parse("ബ്ലൂടൂത്ത് തുറക്കൂ")
        assertNotNull(mlPlan)
        assertEquals("open_settings", mlPlan!!.toolCalls.first().tool)
    }

    @Test
    fun `test 14 explicit language switch request returns zero-tool acknowledgment plan`() {
        val plan = engine.parse("Reply in Telugu please")
        assertNotNull(plan)
        assertTrue(plan!!.toolCalls.isEmpty())
        assertEquals(com.example.domain.model.AppLanguage.TELUGU.shortBadge, plan.responseLanguage)
        assertTrue(plan.assistantReply.contains("తెలుగు"))
    }
}
