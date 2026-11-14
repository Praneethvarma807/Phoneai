package com.example

import com.example.ai.JsonPlanParser
import com.example.voice.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPlanParserAndWakeWordTest {

    @Test
    fun `json plan parser extracts valid registered tools and ignores markdown fences`() {
        val raw = """
            ```json
            {
              "intent": "open_chrome",
              "interpretation": "Launch Chrome browser",
              "steps": ["Find Chrome", "Launch activity"],
              "tool_calls": [
                {"tool": "open_app", "arguments": {"app_name": "Chrome"}}
              ],
              "requires_confirmation": false,
              "assistant_reply": "Opening Chrome."
            }
            ```
        """.trimIndent()

        val parsed = JsonPlanParser.parse(raw, setOf("open_app", "open_url"))
        assertNotNull(parsed)
        assertEquals("open_chrome", parsed!!.intent)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("open_app", parsed.toolCalls.first().tool)
    }

    @Test
    fun `json plan parser blocks unregistered or arbitrary tools`() {
        val raw = """
            {
              "intent": "bad",
              "tool_calls": [
                {"tool": "run_shell_script", "arguments": {"cmd": "rm -rf"}}
              ]
            }
        """.trimIndent()

        val parsed = JsonPlanParser.parse(raw, setOf("open_app", "open_url"))
        assertNull(parsed)
    }

    @Test
    fun `wake word matcher detects Hello Buddy and phonetic variants`() {
        assertTrue(WakeWordMatcher.matchesTranscript("Hello Buddy"))
        assertTrue(WakeWordMatcher.matchesTranscript("hey hello buddy please"))
        assertTrue(WakeWordMatcher.matchesTranscript("Halo buddy"))
        assertTrue(WakeWordMatcher.matchesTranscript("హలో బడ్డీ"))
    }

    @Test
    fun `wake word matcher rejects unrelated phrases to minimize false wakeups`() {
        assertFalse(WakeWordMatcher.matchesTranscript("Hello everybody"))
        assertFalse(WakeWordMatcher.matchesTranscript("Open Chrome"))
        assertFalse(WakeWordMatcher.matchesTranscript("Hey Google"))
        assertFalse(WakeWordMatcher.matchesTranscript(""))
    }

    @Test
    fun `wake word rms and sensitivity thresholds behave consistently`() {
        val silence = ShortArray(160) { 0 }
        assertEquals(0f, WakeWordMatcher.computeFrameRms(silence, silence.size), 0.0001f)

        val speech = ShortArray(160) { 6000 }
        assertTrue(WakeWordMatcher.computeFrameRms(speech, speech.size) > 0.1f)

        val highSensThreshold = WakeWordMatcher.rmsThresholdForSensitivity(0.9f)
        val lowSensThreshold = WakeWordMatcher.rmsThresholdForSensitivity(0.3f)
        assertTrue(highSensThreshold < lowSensThreshold)
    }
}
