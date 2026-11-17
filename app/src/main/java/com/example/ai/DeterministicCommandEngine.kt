package com.example.ai

import com.example.domain.model.ActionPlan
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConversationContextSnapshot
import com.example.domain.model.ConversationTurn
import com.example.domain.model.LanguageDetectionResult
import com.example.domain.model.ToolCallRequest
import com.example.voice.WakeWordMatcher
import java.net.URLEncoder

class DeterministicCommandEngine {

    fun parse(
        rawInput: String,
        languageInfo: LanguageDetectionResult = LanguageDetector.detect(rawInput),
        recentTurns: List<ConversationTurn> = emptyList(),
        contextSnapshot: ConversationContextSnapshot? = null
    ): ActionPlan? {
        val strippedWake = WakeWordMatcher.stripWakeWordPrefix(rawInput)
        val input = strippedWake.trim()
        if (input.isEmpty()) return null
        val effectiveLanguage = if (input.length != rawInput.trim().length) {
            LanguageDetector.detect(input)
        } else {
            languageInfo
        }
        val lower = input.lowercase()
        val langTag = effectiveLanguage.badgeLabel
        val replyLang = effectiveLanguage.responseLanguage

        // Handle pure explicit language-switch acknowledgement (e.g. "Switch to Telugu", "Hindi me bolo")
        val explicitOnly = effectiveLanguage.explicitRequestedLanguage
        if (explicitOnly != null && lower.split(Regex("\\s+")).size <= 5 &&
            !containsCommandVerb(lower)
        ) {
            return ActionPlan(
                intent = "language_switch",
                interpretation = "Switched active conversation language to ${explicitOnly.displayName}.",
                steps = listOf("Set conversation response language to ${explicitOnly.displayName}"),
                toolCalls = emptyList(),
                assistantReply = when (explicitOnly) {
                    AppLanguage.TELUGU -> "సరే, ఇకపై తెలుగులో స్పందిస్తాను."
                    AppLanguage.HINDI -> "ठीक है, अब मैं हिन्दी में जवाब दूँगा।"
                    AppLanguage.TAMIL -> "சரி, இனி தமிழில் பதிலளிக்கிறேன்."
                    AppLanguage.KANNADA -> "ಸರಿ, ಇನ್ನು ಮುಂದೆ ಕನ್ನಡದಲ್ಲಿ ಉತ್ತರಿಸುತ್ತೇನೆ."
                    AppLanguage.MALAYALAM -> "ശരി, ഇനി മലയാളത്തിൽ മറുപടി നൽകാം."
                    else -> "Okay, I will respond in ${explicitOnly.displayName}."
                },
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = explicitOnly.shortBadge
            )
        }

        // Unsupported non-Latin scripts return null gracefully so orchestrator/LLM can handle honestly
        if (effectiveLanguage.primaryLanguage == AppLanguage.UNSUPPORTED) {
            return null
        }

        // 0. Context-aware multi-turn follow-ups
        if (contextSnapshot != null) {
            if (lower in setOf("repeat that", "say that again", "what did you say", "malli cheppu", "phir se bolo")) {
                val lastReply = contextSnapshot.lastSummary
                    ?: recentTurns.lastOrNull { it.role == "assistant" }?.text
                if (!lastReply.isNullOrBlank()) {
                    return ActionPlan(
                        intent = "repeat_last_reply",
                        interpretation = "Repeat previous assistant response.",
                        steps = listOf("Read previous response aloud"),
                        toolCalls = emptyList(),
                        assistantReply = lastReply,
                        engineUsed = "DETERMINISTIC",
                        detectedLanguage = langTag,
                        responseLanguage = replyLang.shortBadge
                    )
                }
            }
            if (lower in setOf("open it", "open that", "open again", "malli open chey", "phir kholo")) {
                val lastUrl = contextSnapshot.lastUrl
                val lastApp = contextSnapshot.lastAppName
                if (!lastUrl.isNullOrBlank()) {
                    return ActionPlan(
                        intent = "open_url",
                        interpretation = "Re-open recent URL from conversation context ($lastUrl).",
                        steps = listOf("Open $lastUrl"),
                        toolCalls = listOf(ToolCallRequest("open_url", mapOf("url" to lastUrl))),
                        assistantReply = "Opening $lastUrl again.",
                        engineUsed = "DETERMINISTIC",
                        detectedLanguage = langTag,
                        responseLanguage = replyLang.shortBadge
                    )
                }
                if (!lastApp.isNullOrBlank()) {
                    return ActionPlan(
                        intent = "open_app",
                        interpretation = "Re-open $lastApp from conversation context.",
                        steps = listOf("Launch $lastApp"),
                        toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to lastApp))),
                        assistantReply = "",
                        engineUsed = "DETERMINISTIC",
                        detectedLanguage = langTag,
                        responseLanguage = replyLang.shortBadge,
                        silentExecution = true
                    )
                }
            }
            if (lower in setOf("louder", "make it louder", "mute that", "quieter")) {
                val dir = if (lower.contains("mute")) "mute" else if (lower.contains("quiet")) "down" else "up"
                return ActionPlan(
                    intent = "adjust_volume",
                    interpretation = "Follow-up volume adjustment ($dir).",
                    steps = listOf("Adjust volume $dir"),
                    toolCalls = listOf(ToolCallRequest("adjust_volume", mapOf("direction" to dir))),
                    assistantReply = "Adjusting volume $dir.",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
        }

        // 1. Screen Understanding, Screenshot & Home Navigation
        if (lower.contains("explain my screen") ||
            lower.contains("what is on my screen") ||
            lower.contains("analyze screen") ||
            lower.contains("read my screen") ||
            lower.contains("screen meeda") ||
            lower.contains("screen explain") ||
            lower.contains("screen par kya") ||
            lower.contains("screen samjhao") ||
            lower.contains("திரை") ||
            lower.contains("ಸ್ಕ್ರೀನ್") ||
            lower.contains("സ്ക്രീൻ") ||
            input.contains("స్క్రీన్") ||
            input.contains("स्क्रीन")
        ) {
            return ActionPlan(
                intent = "explain_screen",
                interpretation = "Capture and analyze visible screen content locally using on-device OCR/Accessibility.",
                steps = listOf("Check screen capture or accessibility access", "Extract visible text locally", "Summarize & suggest next actions"),
                toolCalls = listOf(ToolCallRequest("analyze_screenshot", mapOf("mode" to "explain"))),
                assistantReply = localizedReply(replyLang, "Analyzing your current screen locally.", "ప్రస్తుత స్క్రీన్‌ను లోకల్‌గా విశ్లేషిస్తున్నాను.", "आपकी स्क्रीन का लोकल विश्लेषण कर रहा हूँ।", "உங்கள் திரையை உள்ளூரில் ஆய்வு செய்கிறேன்.", "ನಿಮ್ಮ ಪರದೆಯನ್ನು ಸ್ಥಳೀಯವಾಗಿ ವಿಶ್ಲೇಷಿಸುತ್ತಿದ್ದೇನೆ.", "നിങ്ങളുടെ സ്ക്രീൻ ലോക്കലായി പരിശോധിക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (Regex("\\bscreenshot\\b").containsMatchIn(lower) ||
            lower.contains("capture screen") ||
            lower.contains("screen shot") ||
            input.contains("స్క్రీన్‌షాట్") ||
            input.contains("స్క్రీన్ షాట్") ||
            input.contains("स्क्रीनशॉट")
        ) {
            return ActionPlan(
                intent = "take_screenshot",
                interpretation = "Capture a system screenshot.",
                steps = listOf("Invoke Accessibility global screenshot action"),
                toolCalls = listOf(ToolCallRequest("browser_navigate", mapOf("action" to "screenshot"))),
                assistantReply = "",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge,
                silentExecution = true
            )
        }

        if (Regex("\\b(navigate to home|go to home|go home|home screen)\\b").containsMatchIn(lower) ||
            lower == "home" ||
            input.contains("హోమ్ స్క్రీన్") ||
            input.contains("होम स्क्रीन")
        ) {
            return ActionPlan(
                intent = "navigate_home",
                interpretation = "Navigate to device home screen.",
                steps = listOf("Perform global Home action"),
                toolCalls = listOf(ToolCallRequest("browser_navigate", mapOf("action" to "home"))),
                assistantReply = "",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge,
                silentExecution = true
            )
        }

        // 2. Multi-step Naukri resume headline update
        if (lower.contains("naukri") && (lower.contains("headline") || lower.contains("resume"))) {
            val newHeadline = extractAfterKeywords(input, listOf("headline to ", "headline as ", "to ", "as "))
                ?.trim()
                ?.takeIf { it.length >= 3 }
            if (newHeadline == null) {
                return clarificationPlan(
                    intent = "clarification_naukri_headline",
                    reply = "Please include the new resume headline text (for example: \"Update my Naukri resume headline to Senior SAP FICO Consultant\").",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "naukri_update_headline",
                interpretation = "Open Naukri profile, locate resume headline field, preview update, and request confirmation before submitting.",
                steps = listOf(
                    "Open Naukri profile page",
                    "Read visible screen to verify login & profile state",
                    "Locate headline field & prepare \"$newHeadline\"",
                    "Request explicit user confirmation before saving"
                ),
                toolCalls = listOf(
                    ToolCallRequest("open_url", mapOf("url" to "https://www.naukri.com/mnjuser/profile")),
                    ToolCallRequest("get_screen_text", emptyMap()),
                    ToolCallRequest("browser_type", mapOf("target" to "headline", "text" to newHeadline))
                ),
                requiresConfirmation = true,
                confirmationPrompt = "Confirm updating your Naukri resume headline to: \"$newHeadline\"?",
                assistantReply = "Prepared Naukri resume headline update for your review.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 3. Multi-step Naukri job search
        if (lower.contains("naukri") && (lower.contains("search") || lower.contains("job") || lower.contains("vethuku") || lower.contains("dhundo"))) {
            val query = extractAfterKeywords(input, listOf("naukri for ", "search for ", "jobs for ", "find ", "search "))
                ?.replace(Regex("(?i)\\b(on\\s+naukri|in\\s+naukri|naukri|jobs?)\\b"), " ")
                ?.replace(Regex("(?i)^\\s*for\\b"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.length >= 2 }
            if (query == null) {
                return clarificationPlan(
                    intent = "clarification_naukri_search",
                    reply = "Please specify the job role or skills to search on Naukri (for example: \"Open Naukri and search for Kotlin developer in Bengaluru\").",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            val encoded = encode(query)
            val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return ActionPlan(
                intent = "naukri_job_search",
                interpretation = "Open Naukri and search for \"$query\".",
                steps = listOf(
                    "Open Naukri job search for $query",
                    "Check visible search results via Accessibility if enabled"
                ),
                toolCalls = listOf(
                    ToolCallRequest("open_url", mapOf("url" to "https://www.naukri.com/$slug-jobs?k=$encoded")),
                    ToolCallRequest("get_screen_text", emptyMap())
                ),
                assistantReply = "Opening Naukri job search for $query.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 4. YouTube search (EN + Tenglish + Hinglish + Tanglish + Kanglish + Manglish + native scripts)
        if ((lower.contains("youtube") || input.contains("యూట్యూబ్") || input.contains("यूट्यूब") ||
                input.contains("யூடியூப்") || input.contains("ಯೂಟ್ಯೂಬ್") || input.contains("യൂട്യൂബ്")) &&
            (lower.contains("search") || lower.contains("play") || lower.contains("lo ") ||
                lower.contains("mein ") || lower.contains("par ") || lower.contains("il ") ||
                lower.contains("alli ") || lower.contains("chudu") || lower.contains("thedu") ||
                lower.contains("huduku") || lower.contains("thedoo") || lower.contains("karo") ||
                lower.contains("chey") || input.contains("వెతుకు") || input.contains("खोजो"))
        ) {
            val query = extractYouTubeQuery(input)
            if (!query.isNullOrBlank()) {
                val url = "https://www.youtube.com/results?search_query=${encode(query)}"
                return ActionPlan(
                    intent = "youtube_search",
                    interpretation = "Open YouTube and search for \"$query\".",
                    steps = listOf("Launch YouTube search for $query"),
                    toolCalls = listOf(ToolCallRequest("open_url", mapOf("url" to url))),
                    assistantReply = localizedReply(replyLang, "Searching YouTube for $query.", "యూట్యూబ్‌లో $query కోసం వెతుకుతున్నాను.", "यूट्यूब पर $query खोज रहा हूँ।", "யூடியூபில் $query தேடுகிறேன்.", "ಯೂಟ್ಯೂಬ್‌ನಲ್ಲಿ $query ಹುಡುಕುತ್ತಿದ್ದೇನೆ.", "യൂട്യൂബിൽ $query തിരയുന്നു."),
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
        }

        // 5. WhatsApp message draft (always confirmation-gated)
        if (lower.contains("whatsapp") || input.contains("వాట్సాప్") || input.contains("व्हाट्सएप") ||
            input.contains("வாட்ஸ்அப்") || input.contains("ವಾಟ್ಸಾಪ್") || input.contains("വാട്ട്സ്ആപ്പ്")
        ) {
            val recipient = extractBetween(input, listOf("to ", "ki ", "ko "), listOf(" saying ", " message ", " that ", ":"))
            val message = extractAfterKeywords(input, listOf("saying ", "message ", "that ", "ani "))
            if (!recipient.isNullOrBlank() || !message.isNullOrBlank()) {
                val cleanRecipient = recipient?.trim().orEmpty()
                val cleanMessage = message?.trim().orEmpty()
                return ActionPlan(
                    intent = "draft_whatsapp",
                    interpretation = "Prepare WhatsApp message draft to ${cleanRecipient.ifBlank { "selected contact" }}.",
                    steps = listOf("Search contacts if needed", "Preview WhatsApp message", "Await explicit confirmation"),
                    toolCalls = listOf(
                        ToolCallRequest(
                            "send_whatsapp_draft",
                            mapOf("recipient" to cleanRecipient, "message" to cleanMessage)
                        )
                    ),
                    requiresConfirmation = true,
                    confirmationPrompt = "Open WhatsApp draft to ${cleanRecipient.ifBlank { "contact" }}: \"$cleanMessage\"?",
                    assistantReply = "WhatsApp draft ready for confirmation.",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
            return ActionPlan(
                intent = "open_app",
                interpretation = "Open WhatsApp.",
                steps = listOf("Launch WhatsApp"),
                toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to "WhatsApp"))),
                assistantReply = "",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge,
                silentExecution = true
            )
        }

        // 6. Draft SMS (always confirmation-gated; guard against read/question prompts)
        val isSmsCommand = Regex("\\bsms\\b").containsMatchIn(lower) ||
            (Regex("\\btext\\b").containsMatchIn(lower) && Regex("\\b(to|ki|ko)\\b").containsMatchIn(lower)) ||
            (Regex("\\b(send|draft|write|compose)\\b").containsMatchIn(lower) && Regex("\\bmessage\\b").containsMatchIn(lower)) ||
            Regex("\\bmessage\\s+(to|ki|ko)\\b").containsMatchIn(lower)
        if (isSmsCommand) {
            val recipient = extractBetween(input, listOf("to ", "ki ", "ko "), listOf(" saying ", " that ", " message ", ":"))
                ?: extractAfterKeywords(input, listOf("to "))?.split(" ")?.firstOrNull()
                ?: ""
            val body = extractAfterKeywords(input, listOf("saying ", "that ", "message ", "body ")) ?: ""
            if (recipient.isBlank() && body.isBlank()) {
                return clarificationPlan(
                    intent = "clarification_sms",
                    reply = "Who should I prepare the SMS for and what message should I draft? (For example: \"Draft SMS to Alex saying On my way\")",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "compose_sms",
                interpretation = "Draft an SMS message for explicit confirmation.",
                steps = listOf("Resolve recipient contact", "Prepare SMS draft", "Confirm before opening messaging app"),
                toolCalls = listOf(ToolCallRequest("compose_sms", mapOf("recipient" to recipient.trim(), "body" to body.trim()))),
                requiresConfirmation = true,
                confirmationPrompt = "Confirm drafting SMS to ${recipient.ifBlank { "recipient" }}: \"${body.ifBlank { "(empty)" }}\"?",
                assistantReply = "Prepared SMS draft for confirmation.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 7. Open Gmail or Draft Email (always confirmation-gated)
        if (Regex("\\b(gmail|email|mail)\\b").containsMatchIn(lower)) {
            val isOpenMail = Regex("\\b(open|launch|start)\\s+(gmail|email|mail)\\b").containsMatchIn(lower) ||
                lower in setOf("gmail open chey", "gmail open cheyyi", "gmail kholo", "email kholo")
            if (isOpenMail) {
                return ActionPlan(
                    intent = "open_app",
                    interpretation = "Open Gmail.",
                    steps = listOf("Launch Gmail"),
                    toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to "Gmail"))),
                    assistantReply = "",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge,
                    silentExecution = true
                )
            }
            val recipient = extractBetween(input, listOf("to "), listOf(" about ", " subject ", " saying ", ":")) ?: ""
            val subject = extractBetween(input, listOf("about ", "subject "), listOf(" saying ", " body ")) ?: ""
            val body = extractAfterKeywords(input, listOf("saying ", "body ")) ?: ""
            if (recipient.isBlank() && subject.isBlank() && body.isBlank()) {
                return clarificationPlan(
                    intent = "clarification_email",
                    reply = "Who would you like to email and what is the subject/message? (For example: \"Draft email to hr@example.com about interview saying Thank you\")",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "compose_email",
                interpretation = "Draft an email for explicit confirmation.",
                steps = listOf("Prepare email draft", "Preview recipient and subject", "Await confirmation"),
                toolCalls = listOf(
                    ToolCallRequest(
                        "compose_email",
                        mapOf("to" to recipient.trim(), "subject" to subject.trim(), "body" to body.trim())
                    )
                ),
                requiresConfirmation = true,
                confirmationPrompt = "Confirm drafting email to ${recipient.ifBlank { "recipient" }}?",
                assistantReply = "Prepared email draft for confirmation.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 8. Browser Automation direct controls
        if (lower.startsWith("scroll ")) {
            val dir = if (lower.contains("up")) "up" else "down"
            return ActionPlan(
                intent = "browser_scroll",
                interpretation = "Scroll active window $dir.",
                steps = listOf("Perform accessibility scroll $dir"),
                toolCalls = listOf(ToolCallRequest("browser_scroll", mapOf("direction" to dir))),
                assistantReply = "Scrolling $dir.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }
        if (lower.startsWith("click ") || lower.startsWith("tap ")) {
            val target = input.substringAfter(" ").trim()
            if (target.isBlank()) {
                return clarificationPlan(
                    intent = "clarification_click",
                    reply = "Tell me which button or text on the screen to tap.",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "browser_click",
                interpretation = "Tap UI element matching \"$target\".",
                steps = listOf("Find node \"$target\" via Accessibility Service", "Perform click"),
                toolCalls = listOf(ToolCallRequest("browser_click", mapOf("target" to target))),
                assistantReply = "Tapping $target.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }
        if (lower.startsWith("type ")) {
            val textToType = input.removePrefix("type ").removePrefix("Type ").trim()
            if (textToType.isBlank()) {
                return clarificationPlan(
                    intent = "clarification_type",
                    reply = "Tell me what text to type into the active field.",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "browser_type",
                interpretation = "Type \"$textToType\" into focused input field.",
                steps = listOf("Locate editable input field", "Insert text"),
                toolCalls = listOf(ToolCallRequest("browser_type", mapOf("text" to textToType))),
                assistantReply = "Typing text into focused field.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 9. Settings shortcuts (Multilingual keywords)
        val hasSettingsKeyword = Regex("\\b(settings|wi-?fi|bluetooth)\\b").containsMatchIn(lower) ||
            input.contains("సెట్టింగ్స్") || input.contains("सेटिंग्स") ||
            input.contains("அமைப்புகள்") || input.contains("ಸೆಟ್ಟಿಂಗ್") || input.contains("സെറ്റിംഗ്സ്") ||
            input.contains("బ్లూటూత్") || input.contains("ब्लूटूथ") || input.contains("ப்ளூடூத்") ||
            input.contains("ಬ್ಲೂಟೂತ್") || input.contains("ബ്ലൂടൂത്ത്") ||
            input.contains("వైఫై") || input.contains("वाईफाई") || input.contains("வைஃபை") ||
            input.contains("ವೈಫೈ") || input.contains("വൈഫൈ")
        if (hasSettingsKeyword && (!lower.startsWith("is ") || lower.contains("settings"))) {
            val category = when {
                lower.contains("wi-fi") || lower.contains("wifi") || input.contains("వైఫై") ||
                    input.contains("वाईफाई") || input.contains("வைஃபை") || input.contains("ವೈಫೈ") || input.contains("വൈഫൈ") -> "wifi"
                lower.contains("bluetooth") || input.contains("బ్లూటూత్") || input.contains("ब्लूटूथ") ||
                    input.contains("ப்ளூடூத்") || input.contains("ಬ್ಲೂಟೂತ್") || input.contains("ബ്ലൂടൂത്ത്") -> "bluetooth"
                lower.contains("display") || lower.contains("screen settings") -> "display"
                lower.contains("sound") || lower.contains("audio settings") -> "sound"
                lower.contains("location") -> "location"
                lower.contains("battery") -> "battery"
                lower.contains("accessibility") -> "accessibility"
                lower.contains("app") -> "apps"
                else -> "main"
            }
            return ActionPlan(
                intent = "open_settings",
                interpretation = "Open Android $category settings.",
                steps = listOf("Launch Android $category settings screen"),
                toolCalls = listOf(ToolCallRequest("open_settings", mapOf("section" to category))),
                assistantReply = "",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge,
                silentExecution = true
            )
        }

        // 10. Volume & Brightness (EN + TE + HI + TA + KN + ML)
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("sound") ||
            lower.contains("awaaz") || lower.contains("shabdam") || input.contains("వాల్యూమ్") ||
            input.contains("आवाज़") || input.contains("वॉल्यूम") || input.contains("ஒலி") ||
            input.contains("வால்யூம்") || input.contains("ಧ್ವನಿ") || input.contains("ವಾಲ್ಯೂಮ್") ||
            input.contains("ശബ്ദം") || input.contains("വോളിയം")
        ) {
            val action = when {
                lower.contains("mute") || lower.contains("band") -> "mute"
                lower.contains("increase") || lower.contains("up") || lower.contains("raise") ||
                    lower.contains("louder") || lower.contains("penchu") || lower.contains("badhao") ||
                    lower.contains("athigari") || lower.contains("hechchisu") || lower.contains("koottu") ||
                    input.contains("పెంచు") || input.contains("बढ़ाओ") || input.contains("அதிகரி") ||
                    input.contains("ಹೆಚ್ಚಿಸು") || input.contains("കൂട്ടു") -> "up"
                lower.contains("decrease") || lower.contains("down") || lower.contains("lower") ||
                    lower.contains("quieter") || lower.contains("thagginchu") || lower.contains("kam") ||
                    lower.contains("kurai") || lower.contains("kadime") || lower.contains("kuraykku") ||
                    input.contains("తగ్గించు") || input.contains("कम") || input.contains("குறை") ||
                    input.contains("ಕಡಿಮೆ") || input.contains("കുറയ്ക്കു") -> "down"
                else -> "up"
            }
            return ActionPlan(
                intent = "adjust_volume",
                interpretation = "Adjust media volume ($action).",
                steps = listOf("Update AudioManager media stream volume"),
                toolCalls = listOf(ToolCallRequest("adjust_volume", mapOf("direction" to action))),
                assistantReply = localizedReply(replyLang, "Adjusting volume $action.", "వాల్యూమ్ $action చేస్తున్నాను.", "वॉल्यूम $action कर रहा हूँ।", "ஒலி அளவை $action செய்கிறேன்.", "ಧ್ವನಿ ಮಟ್ಟವನ್ನು $action ಮಾಡುತ್ತಿದ್ದೇನೆ.", "വോളിയം $action ചെയ്യുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("brightness") || input.contains("బ్రైట్‌నెస్") || input.contains("బ్రైట్నెస్") ||
            input.contains("ब्राइटनेस") || input.contains("பிரகாசம்") || input.contains("திரை ஒளி") ||
            input.contains("ಬ್ರೈಟ್ನೆಸ್") || input.contains("ಬ್ರೈಟ್‌ನೆಸ್") || input.contains("ಪ್ರಕಾಶ") ||
            input.contains("ബ്രൈറ്റ്നസ്") || input.contains("വെളിച്ചം")
        ) {
            val numberMatch = Regex("\\d+").find(lower)?.value?.toIntOrNull()?.coerceIn(5, 100)
            val level = when {
                numberMatch != null -> numberMatch
                lower.contains("max") || lower.contains("full") -> 100
                lower.contains("increase") || lower.contains("up") || lower.contains("higher") ||
                    lower.contains("penchu") || lower.contains("badhao") || lower.contains("athigari") ||
                    lower.contains("hechchisu") || lower.contains("koottu") ||
                    input.contains("పెంచు") || input.contains("बढ़ाओ") || input.contains("அதிகரி") ||
                    input.contains("ಹೆಚ್ಚಿಸು") || input.contains("കൂട്ടു") -> 80
                lower.contains("decrease") || lower.contains("down") || lower.contains("low") ||
                    lower.contains("dim") || lower.contains("thagginchu") || lower.contains("kam") ||
                    lower.contains("kurai") || lower.contains("kadime") || lower.contains("kuraykku") ||
                    input.contains("తగ్గించు") || input.contains("कम") || input.contains("குறை") ||
                    input.contains("ಕಡಿಮೆ") || input.contains("കുറയ്ക്കു") -> 25
                else -> 60
            }
            return ActionPlan(
                intent = "adjust_brightness",
                interpretation = "Set screen brightness to $level%.",
                steps = listOf("Verify WRITE_SETTINGS permission", "Update screen brightness"),
                toolCalls = listOf(ToolCallRequest("adjust_brightness", mapOf("level" to level.toString()))),
                assistantReply = localizedReply(replyLang, "Adjusting brightness to $level%.", "బ్రైట్‌నెస్‌ను $level%కి సెట్ చేస్తున్నాను.", "ब्राइटनेस को $level% पर सेट कर रहा हूँ।", "திரை ஒளியை $level% ஆக அமைக்கிறேன்.", "ಬ್ರೈಟ್‌ನೆಸ್ ಅನ್ನು $level% ಗೆ ಹೊಂದಿಸುತ್ತಿದ್ದೇನೆ.", "ബ്രൈറ്റ്നസ് $level% ആക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10b. Flashlight / Torch (Multilingual)
        if (lower.contains("flashlight") || lower.contains("torch") ||
            input.contains("టార్చ్") || input.contains("ఫ్లాష్") ||
            input.contains("टॉर्च") || input.contains("फ्लैश") ||
            input.contains("டார்ச்") || input.contains("ಟಾರ್ಚ್") || input.contains("ടോർച്ച്")
        ) {
            val stateArg = when {
                lower.contains("off") || lower.contains("band") || lower.contains("aapu") ||
                    input.contains("ఆపు") || input.contains("बंद") || input.contains("நிறுத்து") -> "off"
                lower.contains("on") || lower.contains("turn on") || lower.contains("chalu") ||
                    lower.contains("jalao") || input.contains("ఆన్") || input.contains("चालू") -> "on"
                else -> "toggle"
            }
            return ActionPlan(
                intent = "toggle_flashlight",
                interpretation = "Toggle camera flashlight ($stateArg).",
                steps = listOf("Update hardware torch via CameraManager"),
                toolCalls = listOf(ToolCallRequest("toggle_flashlight", mapOf("state" to stateArg))),
                assistantReply = localizedReply(replyLang, "Updating flashlight ($stateArg).", "ఫ్లాష్‌లైట్ మారుస్తున్నాను.", "फ्लैशलाइट बदल रहा हूँ।", "ஃபிளாஷ்லைட்டை மாற்றுகிறேன்.", "ಫ್ಲ್ಯಾಶ್‌ಲೈಟ್ ಬದಲಾಯಿಸುತ್ತಿದ್ದೇನೆ.", "ഫ്ലാഷ്‌ലൈറ്റ് മാറ്റുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10c. Countdown Timer (Multilingual)
        if (lower.contains("timer") || input.contains("టైమర్") || input.contains("टाइमर") ||
            input.contains("டைமர்") || input.contains("ಟೈಮರ್") || input.contains("ടൈമർ")
        ) {
            val amount = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (amount == null || amount <= 0) {
                return clarificationPlan(
                    intent = "clarification_timer",
                    reply = "How many minutes or seconds should I set the timer for? (For example: \"Set a timer for 10 minutes\")",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            val isSeconds = lower.contains("second") || lower.contains("sec")
            val seconds = if (isSeconds) amount.coerceIn(5, 86_400) else (amount * 60).coerceIn(5, 86_400)
            return ActionPlan(
                intent = "set_timer",
                interpretation = "Start a countdown timer for ${if (isSeconds) "$seconds seconds" else "$amount minutes"}.",
                steps = listOf("Launch system Clock timer"),
                toolCalls = listOf(ToolCallRequest("set_timer", mapOf("seconds" to seconds.toString(), "label" to "AI Commander Timer"))),
                assistantReply = "Opening Clock timer for ${if (isSeconds) "$seconds seconds" else "$amount minutes"}.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10d. Battery, Device Info, Network Status
        if ((lower.contains("battery") || input.contains("బ్యాటరీ") || input.contains("बैटरी")) &&
            !lower.contains("settings")
        ) {
            return ActionPlan(
                intent = "get_battery_status",
                interpretation = "Read local battery charge level and charging state.",
                steps = listOf("Inspect BatteryManager telemetry"),
                toolCalls = listOf(ToolCallRequest("get_battery_status")),
                assistantReply = localizedReply(replyLang, "Checking battery level.", "బ్యాటరీ స్థాయిని తనిఖీ చేస్తున్నాను.", "बैटरी स्तर की जांच कर रहा हूँ।", "பேட்டரி அளவைச் சரிபார்க்கிறேன்.", "ಬ್ಯಾಟರಿ ಮಟ್ಟವನ್ನು ಪರಿಶೀಲಿಸುತ್ತಿದ್ದೇನೆ.", "ബാറ്ററി നില പരിശോധിക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("device info") || lower.contains("phone info") || lower.contains("phone specs") ||
            lower.contains("storage info") || lower.contains("ram info") || lower.contains("device status")
        ) {
            return ActionPlan(
                intent = "get_device_info",
                interpretation = "Read device hardware, RAM, ABI, and free storage telemetry.",
                steps = listOf("Collect local device telemetry"),
                toolCalls = listOf(ToolCallRequest("get_device_info")),
                assistantReply = "Checking device specifications and storage.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("network status") || lower.contains("am i online") || lower.contains("am i offline") ||
            lower.contains("internet status") || lower.contains("internet connection") ||
            lower.contains("check internet") || lower.contains("check network")
        ) {
            return ActionPlan(
                intent = "get_network_status",
                interpretation = "Check current online/offline connectivity status.",
                steps = listOf("Query ConnectivityManager"),
                toolCalls = listOf(ToolCallRequest("get_network_status")),
                assistantReply = "Checking network status.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10e. Notification Intelligence Summary
        if (lower.contains("notification") || lower.contains("unread alerts") || lower.contains("my alerts") ||
            input.contains("నోటిఫికేషన్") || input.contains("नोटिफिकेशन")
        ) {
            return ActionPlan(
                intent = "summarize_notifications",
                interpretation = "Summarize non-sensitive captured notifications locally.",
                steps = listOf("Read redacted in-memory notification digest"),
                toolCalls = listOf(ToolCallRequest("summarize_notifications")),
                assistantReply = localizedReply(replyLang, "Summarizing your recent notifications.", "మీ ఇటీవలి నోటిఫికేషన్‌లను సంగ్రహిస్తున్నాను.", "आपकी हाल की सूचनाओं का सारांश बना रहा हूँ।", "உங்கள் சமீபத்திய அறிவிப்புகளைச் சுருக்குகிறேன்.", "ನಿಮ್ಮ ಇತ್ತೀಚಿನ ಅಧಿಸೂಚನೆಗಳನ್ನು ಸಂಕ್ಷಿಪ್ತಗೊಳಿಸುತ್ತಿದ್ದೇನೆ.", "നിങ്ങളുടെ സമീപകാല അറിയിപ്പുകൾ സംഗ്രഹിക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10f. Personal AI Memory (Save / Recall / Forget)
        if (lower.startsWith("remember ") || lower.contains("remember that ") || lower.contains("save to memory ") ||
            lower.contains("save preference ")
        ) {
            val body = extractAfterKeywords(input, listOf("remember that ", "remember my ", "remember ", "save to memory ", "save preference "))
                ?.trim().orEmpty()
            if (body.isNotBlank()) {
                val key = body.split(" is ", " = ", " are ").firstOrNull()?.take(32) ?: body.take(28)
                return ActionPlan(
                    intent = "save_memory",
                    interpretation = "Save non-sensitive personal memory after confirmation.",
                    steps = listOf("Validate against secret/OTP policy", "Confirm before encrypting & saving"),
                    toolCalls = listOf(ToolCallRequest("save_memory", mapOf("key" to key, "value" to body, "category" to "PREFERENCE"))),
                    requiresConfirmation = true,
                    confirmationPrompt = "Save this to encrypted personal memory: \"$body\"?",
                    assistantReply = "Prepared personal memory entry for your confirmation.",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
        }

        if (lower.contains("what do you remember") || lower.contains("recall memory") ||
            lower.contains("show memory") || lower.contains("my memories") || lower.contains("show preferences")
        ) {
            val topic = extractAfterKeywords(input, listOf("about ", "memory ", "memories "))?.trim().orEmpty()
            return ActionPlan(
                intent = "recall_memory",
                interpretation = "Recall encrypted on-device personal memories.",
                steps = listOf("Decrypt matching local memory records"),
                toolCalls = listOf(ToolCallRequest("recall_memory", if (topic.isBlank()) emptyMap() else mapOf("query" to topic))),
                assistantReply = "Checking your saved personal memories.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.startsWith("forget ") || lower.contains("clear memory") || lower.contains("delete memory")) {
            val target = extractAfterKeywords(input, listOf("forget my ", "forget memory ", "forget ", "delete memory "))
                ?.trim().orEmpty().ifBlank { "all" }
            return ActionPlan(
                intent = "forget_memory",
                interpretation = "Delete matching personal AI memory record(s).",
                steps = listOf("Confirm deletion", "Remove encrypted records from Room"),
                toolCalls = listOf(ToolCallRequest("forget_memory", mapOf("query" to target))),
                requiresConfirmation = true,
                confirmationPrompt = "Forget stored personal memory for \"$target\"?",
                assistantReply = "Prepared memory deletion for your confirmation.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10g. Smart Routines
        if (lower.startsWith("run routine") || lower.startsWith("start routine") ||
            lower.contains("morning briefing") || lower.contains("night wind-down") ||
            lower.contains("night wind down") || lower.contains("focus mode")
        ) {
            val routineQuery = extractAfterKeywords(input, listOf("run routine ", "start routine "))
                ?: input
            return ActionPlan(
                intent = "run_routine",
                interpretation = "Execute Smart Automation routine matching \"$routineQuery\".",
                steps = listOf("Lookup configured routine", "Execute sequential tool steps", "Log execution"),
                toolCalls = listOf(ToolCallRequest("run_routine", mapOf("routine_name" to routineQuery.trim()))),
                assistantReply = "Running automation routine.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10h. Dial Phone Number / Contact (confirmation-gated, safe ACTION_DIAL)
        if ((lower.startsWith("call ") || lower.startsWith("dial ")) && !lower.contains("recall")) {
            val target = input.substringAfter(" ").trim()
            if (target.isNotBlank()) {
                return ActionPlan(
                    intent = "dial_phone_number",
                    interpretation = "Prepare Phone dialer for \"$target\" after explicit confirmation.",
                    steps = listOf("Resolve phone digits or contact", "Confirm before opening Phone dialer"),
                    toolCalls = listOf(ToolCallRequest("dial_phone_number", mapOf("recipient" to target))),
                    requiresConfirmation = true,
                    confirmationPrompt = "Open Phone dialer for \"$target\"?",
                    assistantReply = "Prepared dialer action for your confirmation.",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
        }

        // 11. Alarm, Reminder, Calendar, Note
        if (lower.contains("alarm") || input.contains("అలారం") || input.contains("अलार्म") ||
            input.contains("அலாரம்") || input.contains("ಅಲಾರಂ") || input.contains("അലാം")
        ) {
            val timeMatch = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(lower)
            val parsedHour = timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsedHour == null) {
                return clarificationPlan(
                    intent = "clarification_alarm",
                    reply = "What time should I set the alarm for? (For example: \"Set an alarm for 6:30 am\")",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            var hour = parsedHour
            val minute = (timeMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, 59)
            val amPm = timeMatch.groupValues.getOrNull(3).orEmpty()
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            hour = hour.coerceIn(0, 23)
            return ActionPlan(
                intent = "set_alarm",
                interpretation = "Prepare alarm for %02d:%02d.".format(hour, minute),
                steps = listOf("Launch system AlarmClock intent"),
                toolCalls = listOf(ToolCallRequest("set_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to "AI Commander Alarm"))),
                assistantReply = "Opening Clock to confirm alarm for %02d:%02d.".format(hour, minute),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("remind") || lower.contains("reminder") || input.contains("రిమైండర్") ||
            input.contains("रिमाइंडर") || input.contains("நினைவூட்டல்") || input.contains("ಜ್ಞಾಪನೆ") || input.contains("റിമൈൻഡർ")
        ) {
            val extracted = extractAfterKeywords(input, listOf("remind me to ", "remind me ", "create a reminder to ", "create reminder to ", "create reminder ", "set a reminder to ", "set reminder ", "reminder for ", "reminder ", "రిమైండర్ పెట్టు ", "రిమైండర్ ", "रिमाइंडर सेट करो ", "रिमाइंडर ", "நினைவூட்டல் ", "ಜ್ಞಾಪನೆ ", "റിമൈൻഡർ "))
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("me", ignoreCase = true) }
            val stripped = input.replace(Regex("(?i)\\b(please|set|a|an|create|add|make|me|to|for|reminder|remind)\\b"), "").trim()
            val title = extracted ?: stripped.takeIf { it.isNotBlank() }
            if (title == null) {
                return clarificationPlan(
                    intent = "clarification_reminder",
                    reply = "What should I remind you about? (For example: \"Remind me to take medicine\")",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "create_reminder",
                interpretation = "Create local reminder: \"$title\".",
                steps = listOf("Save reminder locally in Room database"),
                toolCalls = listOf(ToolCallRequest("create_reminder", mapOf("title" to title))),
                assistantReply = localizedReply(replyLang, "Created reminder: $title.", "రిమైండర్ సేవ్ చేయబడింది: $title.", "रिमाइंडर सहेजा गया: $title.", "நினைவூட்டல் சேமிக்கப்பட்டது: $title.", "ಜ್ಞಾಪನೆ ಉಳಿಸಲಾಗಿದೆ: $title.", "റിമൈൻഡർ സേവ് ചെയ്തു: $title."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("calendar") || lower.contains("event") || lower.contains("meeting")) {
            val title = extractAfterKeywords(input, listOf("calendar event ", "add event ", "schedule meeting ", "schedule ", "meeting ", "event "))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (title == null) {
                return clarificationPlan(
                    intent = "clarification_calendar",
                    reply = "What is the title of the calendar event or meeting?",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "create_calendar_event",
                interpretation = "Create calendar event: \"$title\".",
                steps = listOf("Open calendar event insertion or write event"),
                toolCalls = listOf(ToolCallRequest("create_calendar_event", mapOf("title" to title))),
                assistantReply = "Preparing calendar event: $title.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (Regex("\\bnotes?\\b").containsMatchIn(lower) || lower.startsWith("write down") || input.contains("నోట్") ||
            input.contains("नोट") || input.contains("குறிப்பு") || input.contains("ಟಿಪ್ಪಣಿ") || input.contains("നോട്ട്")
        ) {
            val extracted = extractAfterKeywords(input, listOf("create a note ", "create note ", "take a note ", "take note ", "save note ", "note ", "write down ", "నోట్ రాయి ", "నోట్ ", "नोट लिखो ", "नोट ", "குறிப்பு ", "ಟಿಪ್ಪಣಿ ", "നോട്ട് "))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val stripped = input.replace(Regex("(?i)\\b(please|create|take|add|make|save|a|an|note|write|down)\\b"), "").trim()
            val content = extracted ?: stripped.takeIf { it.isNotBlank() }
            if (content == null) {
                return clarificationPlan(
                    intent = "clarification_note",
                    reply = "What would you like me to write in the note?",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "create_note",
                interpretation = "Save local note on-device.",
                steps = listOf("Persist note in Room database"),
                toolCalls = listOf(ToolCallRequest("create_note", mapOf("title" to content.take(40), "content" to content))),
                assistantReply = localizedReply(replyLang, "Saved note locally.", "నోట్ లోకల్‌గా సేవ్ చేయబడింది.", "नोट लोकल रूप से सहेजा गया।", "குறிப்பு உள்ளூரில் சேமிக்கப்பட்டது.", "ಟಿಪ್ಪಣಿಯನ್ನು ಸ್ಥಳೀಯವಾಗಿ ಉಳಿಸಲಾಗಿದೆ.", "നോട്ട് ലോക്കലായി സേവ് ചെയ്തു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 12. Files & Downloads & Contacts
        if (Regex("\\bdownloads?\\b").containsMatchIn(lower)) {
            return ActionPlan(
                intent = "open_downloads",
                interpretation = "Open device Downloads folder.",
                steps = listOf("Launch system Downloads viewer"),
                toolCalls = listOf(ToolCallRequest("open_downloads", emptyMap())),
                assistantReply = "Opening Downloads.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (Regex("\\b(files?|pdfs?|documents?)\\b").containsMatchIn(lower)) {
            val query = extractAfterKeywords(input, listOf("search files for ", "search file ", "find file ", "find pdf ", "open pdf ", "find document ", "search document "))
                ?: ""
            return ActionPlan(
                intent = "search_files",
                interpretation = "Search accessible documents via Storage Access Framework / MediaStore.",
                steps = listOf("Query local documents matching \"$query\""),
                toolCalls = listOf(ToolCallRequest("search_files", mapOf("query" to query.trim()))),
                assistantReply = "Searching local documents.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (Regex("\\bcontacts?\\b").containsMatchIn(lower)) {
            val query = extractAfterKeywords(input, listOf("search contacts for ", "find contact ", "contact ")) ?: ""
            return ActionPlan(
                intent = "search_contacts",
                interpretation = "Search contacts for \"$query\".",
                steps = listOf("Check READ_CONTACTS permission", "Query ContactsContract"),
                toolCalls = listOf(ToolCallRequest("search_contacts", mapOf("query" to query.trim()))),
                assistantReply = "Searching contacts.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 13. Web Search / URL navigation
        if (lower.contains("http://") || lower.contains("https://") || lower.contains(".com") || lower.contains(".org") || lower.contains(".in")) {
            val urlToken = input.split(" ").firstOrNull {
                it.contains("http") || it.contains(".com") || it.contains(".org") || it.contains(".in")
            } ?: input
            val normalized = if (urlToken.startsWith("http")) urlToken else "https://$urlToken"
            return ActionPlan(
                intent = "open_url",
                interpretation = "Open URL $normalized.",
                steps = listOf("Launch browser to $normalized"),
                toolCalls = listOf(ToolCallRequest("open_url", mapOf("url" to normalized))),
                assistantReply = "Opening $normalized.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 13b. Weather query -> web search (honest connectivity check; never fabricated)
        if (Regex("\\bweather\\b").containsMatchIn(lower) ||
            input.contains("వాతావరణం") ||
            input.contains("मौसम")
        ) {
            return ActionPlan(
                intent = "web_search",
                interpretation = "Search today's weather forecast via web search.",
                steps = listOf("Open browser weather search"),
                toolCalls = listOf(ToolCallRequest("web_search", mapOf("query" to "today weather forecast"))),
                assistantReply = localizedReply(
                    replyLang,
                    "Checking today's weather via Google Search.",
                    "ఈరోజు వాతావరణం కోసం Googleలో వెతుకుతున్నాను.",
                    "आज के मौसम की जानकारी Google पर खोज रहा हूँ।",
                    "இன்றைய வானிலையை Google-இல் தேடுகிறேன்.",
                    "ಇಂದಿನ ಹವಾಮಾನವನ್ನು Google ನಲ್ಲಿ ಹುಡುಕುತ್ತಿದ್ದೇನೆ.",
                    "ഇന്നത്തെ കാലാവസ്ഥ Google-ൽ തിരയുന്നു."
                ),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.startsWith("search ") || lower.contains("google ") || lower.contains("search chey") ||
            lower.contains("search karo") || lower.contains("thedu") || lower.contains("huduku") || lower.contains("thedoo")
        ) {
            val query = extractAfterKeywords(input, listOf("search the web for ", "search google for ", "search web for ", "search online for ", "search for ", "google ", "search "))
                ?.replace(Regex("(?i)\\b(chey|cheyyi|karo|thedu|huduku|thedoo)\\b"), " ")
                ?.replace(Regex("(?i)^\\s*(the\\s+)?(web|google|internet|online)\\s*$"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (query == null) {
                return clarificationPlan(
                    intent = "clarification_search",
                    reply = "What would you like me to search the web for?",
                    langTag = langTag,
                    replyLang = replyLang
                )
            }
            return ActionPlan(
                intent = "web_search",
                interpretation = "Search web for \"$query\".",
                steps = listOf("Open browser search for $query"),
                toolCalls = listOf(ToolCallRequest("web_search", mapOf("query" to query))),
                assistantReply = "Searching the web for $query.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 14. Check installed app
        if (lower.contains("is ") && lower.contains("installed")) {
            val app = extractBetween(input, listOf("is "), listOf(" installed")) ?: input
            return ActionPlan(
                intent = "check_app_installed",
                interpretation = "Check whether $app is installed.",
                steps = listOf("Query PackageManager launcher apps"),
                toolCalls = listOf(ToolCallRequest("check_app_installed", mapOf("app_name" to app.trim()))),
                assistantReply = "Checking if $app is installed.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 15. Open App (English + Telugu + Hindi + Tamil + Kannada + Malayalam)
        val openAppName = extractOpenAppName(input, lower)
        if (!openAppName.isNullOrBlank()) {
            return ActionPlan(
                intent = "open_app",
                interpretation = "Launch installed application \"$openAppName\".",
                steps = listOf("Find matching launcher package", "Start activity"),
                toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to openAppName))),
                assistantReply = "",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge,
                silentExecution = true
            )
        }

        return null
    }

    private fun extractOpenAppName(input: String, lower: String): String? {
        val prefixes = listOf("open ", "launch ", "start ")
        for (p in prefixes) {
            if (lower.startsWith(p)) return input.substring(p.length).trim()
        }
        val suffixes = listOf(
            "open cheyandi", "open cheyyi", "open chey", "teruvu", "theruvu", "తెరువు",
            "kholiye", "kholo", "खोलो",
            "thirakkavum", "thira", "திற",
            "tereyiri", "tere", "ತೆರೆ",
            "thurakkuka", "thurakku", "തുറക്കൂ"
        )
        for (s in suffixes) {
            if (lower.endsWith(s)) {
                return input.dropLast(s.length).trim()
            }
        }
        return null
    }

    private fun containsCommandVerb(lower: String): Boolean = listOf(
        "open", "launch", "start", "search", "volume", "brightness", "reminder", "note",
        "alarm", "settings", "youtube", "whatsapp", "email", "sms", "screen", "screenshot",
        "home", "weather", "bluetooth", "wifi", "wi-fi", "scroll", "click", "tap", "type",
        "flash", "torch", "timer", "battery", "network", "device", "call", "dial",
        "remember", "recall", "forget", "notification", "routine",
        "teruvu", "kholo", "thira", "tere", "thurakku", "penchu", "thagginchu", "badhao"
    ).any { lower.contains(it) }

    private fun localizedReply(
        language: AppLanguage,
        en: String,
        te: String,
        hi: String,
        ta: String,
        kn: String,
        ml: String
    ): String = when (language) {
        AppLanguage.TELUGU -> te
        AppLanguage.HINDI -> hi
        AppLanguage.TAMIL -> ta
        AppLanguage.KANNADA -> kn
        AppLanguage.MALAYALAM -> ml
        else -> en
    }

    private fun extractYouTubeQuery(input: String): String? {
        val cleaned = input
            .replace(Regex("యూట్యూబ్‌లో|యూట్యూబ్ లో|యూట్యూబ్|यूट्यूब पर|यूट्यूब में|यूट्यूब|யூடியூபில்|யூடியூப்|ಯೂಟ್ಯೂಬ್‌ನಲ್ಲಿ|ಯೂಟ್ಯೂಬ್|യൂട്യൂബിൽ|യൂട്യൂബ്"), " ")
            .replace(Regex("(?i)\\byoutube\\b\\s*(lo|mein|par|pe|il|alli|on|for|and)?"), " ")
            .replace(Regex("వెతుకు|ఖోజో|खोजो|चलाओ"), " ")
            .replace(Regex("(?i)\\b(open|and|search|for|chey|cheyyi|chudu|play|karo|thedu|huduku|thedoo)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun extractAfterKeywords(input: String, keywords: List<String>): String? {
        val lower = input.lowercase()
        for (kw in keywords) {
            val idx = lower.indexOf(kw.lowercase())
            if (idx >= 0) {
                val res = input.substring(idx + kw.length).trim()
                if (res.isNotBlank()) return res
            }
        }
        return null
    }

    private fun extractBetween(input: String, starts: List<String>, ends: List<String>): String? {
        val lower = input.lowercase()
        for (s in starts) {
            val startIdx = lower.indexOf(s.lowercase())
            if (startIdx >= 0) {
                val afterStart = startIdx + s.length
                val endIdx = ends.mapNotNull { e ->
                    val idx = lower.indexOf(e.lowercase(), afterStart)
                    if (idx >= 0) idx else null
                }.minOrNull() ?: input.length
                val sub = input.substring(afterStart, endIdx).trim()
                if (sub.isNotBlank()) return sub
            }
        }
        return null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun clarificationPlan(
        intent: String,
        reply: String,
        langTag: String,
        replyLang: AppLanguage
    ): ActionPlan = ActionPlan(
        intent = intent,
        interpretation = "Need missing details before running command.",
        steps = listOf("Clarify missing command details"),
        toolCalls = emptyList(),
        assistantReply = reply,
        engineUsed = "DETERMINISTIC",
        detectedLanguage = langTag,
        responseLanguage = replyLang.shortBadge
    )
}
