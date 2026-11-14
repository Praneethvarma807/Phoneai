package com.example.ai

import com.example.domain.model.AppLanguage
import com.example.domain.model.LanguageDetectionResult
import com.example.domain.model.SpeechLanguageOption

/**
 * Deterministic, zero-API-key, sub-millisecond language detector supporting:
 * - Native scripts: Telugu, Hindi (Devanagari), Tamil, Kannada, Malayalam, English (Latin)
 * - Romanized Indic & mixed-language inputs (Tenglish, Hinglish, Tanglish, Kanglish, Manglish)
 * - Explicit mid-conversation language switches ("reply in Telugu", "Hindi me bolo", etc.)
 * - Graceful UNSUPPORTED classification for non-supported scripts (e.g. Arabic, CJK, Cyrillic)
 */
object LanguageDetector {

    private val teluguMarkers = setOf(
        "chey", "cheyyi", "cheyandi", "teruvu", "theruvu", "chudu", "chupinchu",
        "penchu", "thagginchu", "tagginchu", "lo", "meeda", "naku", "naaku",
        "pampu", "pettandi", "pettu", "rayi", "raayi", "kavali", "undi", "enti",
        "ela", "ekkada", "chelli", "anna", "thammudu", "velu", "vethuku", "vetuku",
        "cheppu", "cheppandi", "telugu"
    )

    private val hindiMarkers = setOf(
        "kholo", "kholiye", "karo", "kijiye", "bhejo", "bhej", "badhao", "kam",
        "ghatao", "mein", "me", "par", "pe", "kya", "dikhao", "likho", "batao",
        "chalu", "band", "sunao", "dhundo", "khojo", "lagao", "banao", "bolo",
        "samjhao", "mujhe", "mera", "meri", "karna", "hindi"
    )

    private val tamilMarkers = setOf(
        "thira", "thirakkavum", "thedu", "thedungal", "anuppu", "anuppungal",
        "athigari", "kurai", "kuraikkavum", "enna", "pannu", "pannungal",
        "sollu", "sollungal", "kaattu", "ezhuthu", "vai", "vaikkavum",
        "il", "ul", "enaku", "enakku", "tamil"
    )

    private val kannadaMarkers = setOf(
        "tere", "tereyiri", "huduku", "huduki", "kaluhu", "kaluhisi",
        "hechchisu", "kadime", "maadu", "maadi", "heli", "heliri", "torsi",
        "bare", "idu", "alli", "inda", "nanage", "nanna", "yaake", "kannada"
    )

    private val malayalamMarkers = setOf(
        "thurakku", "thurakkuka", "thedoo", "theduka", "ayaykku", "ayakkuka",
        "koottu", "koottuka", "kuraykku", "kurakkuka", "cheyyu", "cheyyuka",
        "parayu", "parayuka", "kanikku", "ezhuthu", "vekkuka", "enikku",
        "ente", "enthu", "malayalam"
    )

    private val commonEnglishWords = setOf(
        "open", "launch", "start", "search", "find", "create", "set", "make",
        "adjust", "increase", "decrease", "mute", "volume", "brightness",
        "settings", "bluetooth", "wifi", "wi-fi", "reminder", "note", "alarm",
        "calendar", "event", "meeting", "email", "mail", "sms", "text",
        "message", "whatsapp", "screen", "explain", "analyze", "read",
        "scroll", "click", "tap", "type", "file", "files", "downloads",
        "contacts", "please", "the", "for", "and", "to", "my", "on", "in",
        "is", "installed", "reply", "speak", "respond", "translate", "english"
    )

    fun detect(
        text: String,
        selectedPreference: SpeechLanguageOption = SpeechLanguageOption.AUTO,
        conversationActiveLanguage: AppLanguage? = null
    ): LanguageDetectionResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            val fallback = selectedPreference.appLanguage ?: conversationActiveLanguage ?: AppLanguage.ENGLISH
            return LanguageDetectionResult(primaryLanguage = fallback, confidence = 0.5f)
        }

        val explicitSwitch = detectExplicitRequestedLanguage(trimmed)

        // 1. Inspect Unicode scripts accurately
        var latinCount = 0
        var teluguCount = 0
        var devanagariCount = 0
        var tamilCount = 0
        var kannadaCount = 0
        var malayalamCount = 0
        var otherScriptCount = 0

        for (ch in trimmed) {
            if (!ch.isLetter()) continue
            val code = ch.code
            when (code) {
                in 0x0C00..0x0C7F -> teluguCount++
                in 0x0900..0x097F -> devanagariCount++
                in 0x0B80..0x0BFF -> tamilCount++
                in 0x0C80..0x0CFF -> kannadaCount++
                in 0x0D00..0x0D7F -> malayalamCount++
                in 0x0041..0x024F -> latinCount++
                else -> otherScriptCount++
            }
        }

        val indicScriptCounts = mapOf(
            AppLanguage.TELUGU to teluguCount,
            AppLanguage.HINDI to devanagariCount,
            AppLanguage.TAMIL to tamilCount,
            AppLanguage.KANNADA to kannadaCount,
            AppLanguage.MALAYALAM to malayalamCount
        )

        val topIndicScript = indicScriptCounts.maxByOrNull { it.value }
        if (topIndicScript != null && topIndicScript.value > 0) {
            val hasEnglish = latinCount >= 2
            return LanguageDetectionResult(
                primaryLanguage = topIndicScript.key,
                secondaryLanguage = if (hasEnglish) AppLanguage.ENGLISH else null,
                isMixed = hasEnglish,
                explicitRequestedLanguage = explicitSwitch,
                bcp47Tag = topIndicScript.key.bcp47,
                confidence = 0.98f
            )
        }

        // Non-supported script (e.g. Arabic, Japanese, Chinese, Korean, Cyrillic) with negligible Latin
        if (otherScriptCount > 0 && latinCount == 0) {
            return LanguageDetectionResult(
                primaryLanguage = AppLanguage.UNSUPPORTED,
                secondaryLanguage = null,
                isMixed = false,
                explicitRequestedLanguage = explicitSwitch,
                bcp47Tag = AppLanguage.ENGLISH.bcp47,
                confidence = 0.9f
            )
        }

        // 2. Romanized Indic & mixed-language token analysis
        val tokens = trimmed.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val scores = mutableMapOf(
            AppLanguage.TELUGU to 0,
            AppLanguage.HINDI to 0,
            AppLanguage.TAMIL to 0,
            AppLanguage.KANNADA to 0,
            AppLanguage.MALAYALAM to 0
        )
        var englishHits = 0

        for (token in tokens) {
            if (token in teluguMarkers) scores[AppLanguage.TELUGU] = scores.getValue(AppLanguage.TELUGU) + 2
            if (token in hindiMarkers) scores[AppLanguage.HINDI] = scores.getValue(AppLanguage.HINDI) + 2
            if (token in tamilMarkers) scores[AppLanguage.TAMIL] = scores.getValue(AppLanguage.TAMIL) + 2
            if (token in kannadaMarkers) scores[AppLanguage.KANNADA] = scores.getValue(AppLanguage.KANNADA) + 2
            if (token in malayalamMarkers) scores[AppLanguage.MALAYALAM] = scores.getValue(AppLanguage.MALAYALAM) + 2
            if (token in commonEnglishWords) englishHits++
        }

        // Disambiguate shared markers using contextual bigrams (e.g. "lo" vs "il" vs "mein")
        val lower = trimmed.lowercase()
        if (Regex("\\b(chey|cheyyi|cheyandi|penchu|thagginchu|teruvu|chudu)\\b").containsMatchIn(lower)) {
            scores[AppLanguage.TELUGU] = scores.getValue(AppLanguage.TELUGU) + 3
        }
        if (Regex("\\b(kholo|karo|bhejo|badhao|dikhao|mein)\\b").containsMatchIn(lower)) {
            scores[AppLanguage.HINDI] = scores.getValue(AppLanguage.HINDI) + 3
        }
        if (Regex("\\b(thira|thedu|anuppu|athigari|pannu|sollu)\\b").containsMatchIn(lower)) {
            scores[AppLanguage.TAMIL] = scores.getValue(AppLanguage.TAMIL) + 3
        }
        if (Regex("\\b(tere|huduku|kaluhu|hechchisu|maadu|heli)\\b").containsMatchIn(lower)) {
            scores[AppLanguage.KANNADA] = scores.getValue(AppLanguage.KANNADA) + 3
        }
        if (Regex("\\b(thurakku|thedoo|ayaykku|koottu|kuraykku|cheyyu|parayu)\\b").containsMatchIn(lower)) {
            scores[AppLanguage.MALAYALAM] = scores.getValue(AppLanguage.MALAYALAM) + 3
        }

        // Preference hint only breaks ties if romanized tokens are ambiguous (don't override strong evidence)
        selectedPreference.appLanguage?.let { pref ->
            if (pref in scores && scores.values.max() == 0 && englishHits == 0) {
                scores[pref] = 1
            }
        }

        val bestIndic = scores.maxByOrNull { it.value }
        if (bestIndic != null && bestIndic.value >= 2) {
            val mixed = englishHits > 0
            return LanguageDetectionResult(
                primaryLanguage = bestIndic.key,
                secondaryLanguage = if (mixed) AppLanguage.ENGLISH else null,
                isMixed = mixed,
                explicitRequestedLanguage = explicitSwitch,
                bcp47Tag = bestIndic.key.bcp47,
                confidence = if (bestIndic.value >= 4) 0.92f else 0.78f
            )
        }

        // Pure English tokens detected
        if (englishHits > 0 || latinCount > 0) {
            // If user explicitly asked for a language earlier and only sent an ambiguous single word/number
            val inferred = if (englishHits == 0 && tokens.size <= 2 && conversationActiveLanguage != null && conversationActiveLanguage != AppLanguage.UNSUPPORTED) {
                conversationActiveLanguage
            } else {
                AppLanguage.ENGLISH
            }
            return LanguageDetectionResult(
                primaryLanguage = inferred,
                secondaryLanguage = null,
                isMixed = false,
                explicitRequestedLanguage = explicitSwitch,
                bcp47Tag = inferred.bcp47,
                confidence = if (englishHits > 0) 0.94f else 0.7f
            )
        }

        val fallback = selectedPreference.appLanguage ?: conversationActiveLanguage ?: AppLanguage.ENGLISH
        return LanguageDetectionResult(
            primaryLanguage = fallback,
            explicitRequestedLanguage = explicitSwitch,
            bcp47Tag = fallback.bcp47,
            confidence = 0.6f
        )
    }

    fun detectExplicitRequestedLanguage(text: String): AppLanguage? {
        val lower = text.lowercase()
        val requestTrigger = Regex(
            "(reply in|respond in|answer in|speak in|talk in|switch to|use|lo cheppu|lo cheppandi|lo matladu|me bolo|mein bolo|me baat|il sollu|il pesu|alli heli|alli matadu|il parayu|il samsarikku|తెలుగులో|हिंदी में|हिन्दी में|தமிழில்|ಕನ್ನಡದಲ್ಲಿ|മലയാളത്തിൽ)"
        ).containsMatchIn(lower)
        if (!requestTrigger) return null

        return when {
            lower.contains("telugu") || lower.contains("తెలుగు") -> AppLanguage.TELUGU
            lower.contains("hindi") || lower.contains("हिन्दी") || lower.contains("हिंदी") -> AppLanguage.HINDI
            lower.contains("tamil") || lower.contains("தமிழ்") || lower.contains("தமிழி") -> AppLanguage.TAMIL
            lower.contains("kannada") || lower.contains("ಕನ್ನಡ") -> AppLanguage.KANNADA
            lower.contains("malayalam") || lower.contains("മലയാളം") || lower.contains("മലയാള") -> AppLanguage.MALAYALAM
            lower.contains("english") -> AppLanguage.ENGLISH
            else -> null
        }
    }

    fun unsupportedMessage(language: AppLanguage, command: String): String = when (language) {
        AppLanguage.TELUGU -> "క్షమించండి, \"$command\" కోసం లోకల్ కమాండ్ గుర్తించబడలేదు. దయచేసి స్పష్టమైన కమాండ్ ఇవ్వండి లేదా Models లో లోకల్ AI మోడల్ లోడ్ చేయండి."
        AppLanguage.HINDI -> "क्षमा करें, \"$command\" के लिए कोई समर्थित कमांड नहीं मिला। कृपया स्पष्ट निर्देश दें या Models में लोकल AI मॉडल लोड करें।"
        AppLanguage.TAMIL -> "மன்னிக்கவும், \"$command\" கட்டளையை அடையாளம் காண முடியவில்லை. தெளிவான கட்டளையை உள்ளிடவும் அல்லது Models-இல் உள்ளூர் AI மாதிரியை ஏற்றவும்."
        AppLanguage.KANNADA -> "ಕ್ಷಮಿಸಿ, \"$command\" ಆದೇಶವನ್ನು ಗುರುತಿಸಲು ಸಾಧ್ಯವಾಗಲಿಲ್ಲ. ಸ್ಪಷ್ಟವಾದ ಆದೇಶವನ್ನು ನೀಡಿ ಅಥವಾ Models ನಲ್ಲಿ ಲೋಕಲ್ AI ಮಾದರಿಯನ್ನು ಲೋಡ್ ಮಾಡಿ."
        AppLanguage.MALAYALAM -> "ക്ഷമിക്കണം, \"$command\" കമാൻഡ് തിരിച്ചറിയാനായില്ല. വ്യക്തമായ നിർദ്ദേശം നൽകുക അല്ലെങ്കിൽ Models-ൽ ലോക്കൽ AI മോഡൽ ലോഡ് ചെയ്യുക."
        AppLanguage.UNSUPPORTED -> "This language or script is not yet supported for local command parsing. Supported languages: English, Telugu, Hindi, Tamil, Kannada, and Malayalam."
        AppLanguage.ENGLISH -> "Could not map \"$command\" to a supported phone action. Please rephrase clearly or load a local AI model in Models."
    }

    fun localizedGreetingPrompt(language: AppLanguage): String = when (language) {
        AppLanguage.TELUGU -> "అవును, వింటున్నాను. చెప్పండి."
        AppLanguage.HINDI -> "हाँ, मैं सुन रहा हूँ। बोलिए।"
        AppLanguage.TAMIL -> "ஆம், கேட்கிறேன். சொல்லுங்கள்."
        AppLanguage.KANNADA -> "ಹೌದು, ಕೇಳುತ್ತಿದ್ದೇನೆ. ಹೇಳಿ."
        AppLanguage.MALAYALAM -> "അതെ, ഞാൻ കേൾക്കുന്നു. പറയൂ."
        else -> "Yes, I'm listening."
    }
}
