package com.example

import com.example.ai.LanguageDetector
import com.example.domain.model.AppLanguage
import com.example.domain.model.SpeechLanguageOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `detects native scripts accurately for all 6 supported languages`() {
        assertEquals(AppLanguage.ENGLISH, LanguageDetector.detect("Open Chrome and search weather").primaryLanguage)
        assertEquals(AppLanguage.TELUGU, LanguageDetector.detect("క్రోమ్ ఓపెన్ చేయి").primaryLanguage)
        assertEquals(AppLanguage.HINDI, LanguageDetector.detect("ब्लूटूथ सेटिंग्स खोलो").primaryLanguage)
        assertEquals(AppLanguage.TAMIL, LanguageDetector.detect("வால்யூம் அதிகரிக்கவும்").primaryLanguage)
        assertEquals(AppLanguage.KANNADA, LanguageDetector.detect("ಬ್ಲೂಟೂತ್ ಸೆಟ್ಟಿಂಗ್ಸ್ ತೆರೆಯಿರಿ").primaryLanguage)
        assertEquals(AppLanguage.MALAYALAM, LanguageDetector.detect("ബ്ലൂടൂത്ത് തുറക്കുക").primaryLanguage)
    }

    @Test
    fun `detects romanized and mixed indic-english conversations accurately`() {
        val tenglish = LanguageDetector.detect("YouTube lo Java tutorials search chey")
        assertEquals(AppLanguage.TELUGU, tenglish.primaryLanguage)
        assertTrue(tenglish.isMixed)
        assertEquals(AppLanguage.ENGLISH, tenglish.secondaryLanguage)
        assertEquals("TE + EN", tenglish.badgeLabel)

        val hinglish = LanguageDetector.detect("Bluetooth settings kholo please")
        assertEquals(AppLanguage.HINDI, hinglish.primaryLanguage)
        assertTrue(hinglish.isMixed)
        assertEquals("HI + EN", hinglish.badgeLabel)

        val tanglish = LanguageDetector.detect("Chrome thira")
        assertEquals(AppLanguage.TAMIL, tanglish.primaryLanguage)

        val kanglish = LanguageDetector.detect("Brightness hechchisu maadu")
        assertEquals(AppLanguage.KANNADA, kanglish.primaryLanguage)

        val manglish = LanguageDetector.detect("Settings thurakku")
        assertEquals(AppLanguage.MALAYALAM, manglish.primaryLanguage)
    }

    @Test
    fun `detects mid-conversation dynamic switching without misidentifying languages`() {
        val turn1 = LanguageDetector.detect("YouTube lo Android tutorials search chey")
        assertEquals(AppLanguage.TELUGU, turn1.responseLanguage)

        val turn2 = LanguageDetector.detect("यूट्यूब पर कोटलिन खोजो", conversationActiveLanguage = turn1.responseLanguage)
        assertEquals(AppLanguage.HINDI, turn2.responseLanguage)

        val turn3 = LanguageDetector.detect("யூடியூபில் Flutter தேடு", conversationActiveLanguage = turn2.responseLanguage)
        assertEquals(AppLanguage.TAMIL, turn3.responseLanguage)

        val turn4 = LanguageDetector.detect("Open Bluetooth settings", conversationActiveLanguage = turn3.responseLanguage)
        assertEquals(AppLanguage.ENGLISH, turn4.responseLanguage)
    }

    @Test
    fun `detects explicit language switch directives in English and native scripts`() {
        val switchTelugu = LanguageDetector.detect("Reply in Telugu from now on")
        assertEquals(AppLanguage.TELUGU, switchTelugu.explicitRequestedLanguage)
        assertEquals(AppLanguage.TELUGU, switchTelugu.responseLanguage)

        val switchHindi = LanguageDetector.detect("Hindi me bolo")
        assertEquals(AppLanguage.HINDI, switchHindi.explicitRequestedLanguage)
        assertEquals(AppLanguage.HINDI, switchHindi.responseLanguage)

        val switchTamil = LanguageDetector.detect("தமிழில் பேசு")
        assertEquals(AppLanguage.TAMIL, switchTamil.explicitRequestedLanguage)
        assertEquals(AppLanguage.TAMIL, switchTamil.responseLanguage)

        val switchKannada = LanguageDetector.detect("Switch to Kannada")
        assertEquals(AppLanguage.KANNADA, switchKannada.explicitRequestedLanguage)
        assertEquals(AppLanguage.KANNADA, switchKannada.responseLanguage)

        val switchMalayalam = LanguageDetector.detect("Respond in Malayalam")
        assertEquals(AppLanguage.MALAYALAM, switchMalayalam.explicitRequestedLanguage)
        assertEquals(AppLanguage.MALAYALAM, switchMalayalam.responseLanguage)

        assertNull(LanguageDetector.detectExplicitRequestedLanguage("Open Chrome"))
    }

    @Test
    fun `preserves active conversation language for ambiguous short inputs`() {
        val preserved = LanguageDetector.detect(
            text = "ok 70",
            selectedPreference = SpeechLanguageOption.AUTO,
            conversationActiveLanguage = AppLanguage.KANNADA
        )
        assertEquals(AppLanguage.KANNADA, preserved.primaryLanguage)
        assertFalse(preserved.isMixed)
    }

    @Test
    fun `detects unsupported scripts gracefully and returns localized fallbacks`() {
        val japanese = LanguageDetector.detect("こんにちは、設定を開いて")
        assertEquals(AppLanguage.UNSUPPORTED, japanese.primaryLanguage)
        assertEquals(AppLanguage.UNSUPPORTED.shortBadge, japanese.badgeLabel)

        val arabic = LanguageDetector.detect("افتح الإعدادات")
        assertEquals(AppLanguage.UNSUPPORTED, arabic.primaryLanguage)

        AppLanguage.entries.forEach { lang ->
            val msg = LanguageDetector.unsupportedMessage(lang, "test")
            assertTrue("Missing localized unsupported msg for $lang", msg.isNotBlank())
        }
    }
}
