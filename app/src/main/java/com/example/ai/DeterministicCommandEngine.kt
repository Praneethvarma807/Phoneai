package com.example.ai

import com.example.domain.model.ActionPlan
import com.example.domain.model.AppLanguage
import com.example.domain.model.LanguageDetectionResult
import com.example.domain.model.ToolCallRequest
import java.net.URLEncoder

class DeterministicCommandEngine {

    fun parse(
        rawInput: String,
        languageInfo: LanguageDetectionResult = LanguageDetector.detect(rawInput)
    ): ActionPlan? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null
        val lower = input.lowercase()
        val langTag = languageInfo.badgeLabel
        val replyLang = languageInfo.responseLanguage

        // Handle pure explicit language-switch acknowledgement (e.g. "Switch to Telugu", "Hindi me bolo")
        val explicitOnly = languageInfo.explicitRequestedLanguage
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
        if (languageInfo.primaryLanguage == AppLanguage.UNSUPPORTED) {
            return null
        }

        // 1. Screen Understanding (EN + TE + HI + TA + KN + ML)
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

        // 2. Multi-step Naukri resume headline update
        if (lower.contains("naukri") && (lower.contains("headline") || lower.contains("resume"))) {
            val newHeadline = extractAfterKeywords(input, listOf("headline to ", "headline as ", "to ", "as "))
                ?.takeIf { it.length >= 3 }
                ?: "Experienced Software & Automation Engineer"
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
            val query = extractAfterKeywords(input, listOf("search for ", "search ", "jobs for ", "find "))
                ?: "SAP FICO jobs in Hyderabad"
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
        if (lower.contains("whatsapp") || input.contains("వాట్సాప్") || input.contains("व्हाट्सएप")) {
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
                assistantReply = localizedReply(replyLang, "Opening WhatsApp.", "వాట్సాప్ తెరుస్తున్నాను.", "व्हाट्सएप खोल रहा हूँ।", "வாட்ஸ்அப்பைத் திறக்கிறேன்.", "ವಾಟ್ಸಾಪ್ ತೆರೆಯುತ್ತಿದ್ದೇನೆ.", "വാട്ട്സ്ആപ്പ് തുറക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 6. Draft SMS (always confirmation-gated)
        if (lower.contains("sms") || (lower.contains("text ") && lower.contains(" to ")) || lower.contains("message ")) {
            val recipient = extractBetween(input, listOf("to ", "ki ", "ko "), listOf(" saying ", " that ", " message ", ":"))
                ?: extractAfterKeywords(input, listOf("to "))?.split(" ")?.firstOrNull()
                ?: ""
            val body = extractAfterKeywords(input, listOf("saying ", "that ", "message ", "body ")) ?: ""
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

        // 7. Draft Email (always confirmation-gated)
        if (lower.contains("email") || lower.contains("mail ")) {
            if (lower == "open gmail" || lower == "open email" || lower == "gmail open chey" || lower == "gmail kholo") {
                return ActionPlan(
                    intent = "open_app",
                    interpretation = "Open Gmail.",
                    steps = listOf("Launch Gmail"),
                    toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to "Gmail"))),
                    assistantReply = "Opening Gmail.",
                    engineUsed = "DETERMINISTIC",
                    detectedLanguage = langTag,
                    responseLanguage = replyLang.shortBadge
                )
            }
            val recipient = extractBetween(input, listOf("to "), listOf(" about ", " subject ", " saying ", ":")) ?: ""
            val subject = extractBetween(input, listOf("about ", "subject "), listOf(" saying ", " body ")) ?: ""
            val body = extractAfterKeywords(input, listOf("saying ", "body ")) ?: ""
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
        if (lower.contains("settings") || lower.contains("wi-fi") || lower.contains("wifi") ||
            lower.contains("bluetooth") || input.contains("సెట్టింగ్స్") || input.contains("सेटिंग्स") ||
            input.contains("அமைப்புகள்") || input.contains("ಸೆಟ್ಟಿಂಗ್") || input.contains("സെറ്റിംഗ്സ്") ||
            input.contains("బ్లూటూత్") || input.contains("ब्लूटूथ") || input.contains("ப்ளூடூத்") ||
            input.contains("ಬ್ಲೂಟೂತ್") || input.contains("ബ്ലൂടൂത്ത്") ||
            input.contains("వైఫై") || input.contains("वाईफाई") || input.contains("வைஃபை") ||
            input.contains("ವೈಫೈ") || input.contains("വൈഫൈ")
        ) {
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
                assistantReply = localizedReply(replyLang, "Opening $category settings.", "$category సెట్టింగ్స్ తెరుస్తున్నాను.", "$category सेटिंग्स खोल रहा हूँ।", "$category அமைப்புகளைத் திறக்கிறேன்.", "$category ಸೆಟ್ಟಿಂಗ್‌ಗಳನ್ನು ತೆರೆಯುತ್ತಿದ್ದೇನೆ.", "$category സെറ്റിംഗ്സ് തുറക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 10. Volume & Brightness (EN + TE + HI + TA + KN + ML)
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("sound") ||
            lower.contains("awaaz") || lower.contains("shabdam") || input.contains("వాల్యూమ్") ||
            input.contains("आवाज़") || input.contains("वॉल्यूम") || input.contains("ஒலி") ||
            input.contains("ಧ್ವನಿ") || input.contains("ശബ്ദം") || input.contains("വോളിയം")
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

        // 11. Alarm, Reminder, Calendar, Note
        if (lower.contains("alarm") || input.contains("అలారం") || input.contains("अलार्म") ||
            input.contains("அலாரம்") || input.contains("ಅಲಾರಂ") || input.contains("അലാം")
        ) {
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)
            var hour = timeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
            val minute = timeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val amPm = timeMatch?.groupValues?.getOrNull(3).orEmpty()
            if (amPm == "pm" && hour < 12) hour += 12
            if (amPm == "am" && hour == 12) hour = 0
            return ActionPlan(
                intent = "set_alarm",
                interpretation = "Set alarm for %02d:%02d.".format(hour, minute),
                steps = listOf("Launch system AlarmClock intent"),
                toolCalls = listOf(ToolCallRequest("set_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to "AI Commander Alarm"))),
                assistantReply = "Setting alarm for %02d:%02d.".format(hour, minute),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("remind") || lower.contains("reminder") || input.contains("రిమైండర్") ||
            input.contains("रिमाइंडर") || input.contains("நினைவூட்டல்") || input.contains("ಜ್ಞಾಪನೆ") || input.contains("റിമൈൻഡർ")
        ) {
            val title = extractAfterKeywords(input, listOf("remind me to ", "create a reminder to ", "create reminder ", "reminder for ", "reminder ", "రిమైండర్ ", "रिमाइंडर ", "நினைவூட்டல் ", "ಜ್ಞಾಪನೆ ", "റിമൈൻഡർ "))
                ?: input
            return ActionPlan(
                intent = "create_reminder",
                interpretation = "Create local reminder: \"$title\".",
                steps = listOf("Save reminder locally in Room database"),
                toolCalls = listOf(ToolCallRequest("create_reminder", mapOf("title" to title.trim()))),
                assistantReply = localizedReply(replyLang, "Created reminder: ${title.trim()}.", "రిమైండర్ సేవ్ చేయబడింది: ${title.trim()}.", "रिमाइंडर सहेजा गया: ${title.trim()}.", "நினைவூட்டல் சேமிக்கப்பட்டது: ${title.trim()}.", "ಜ್ಞಾಪನೆ ಉಳಿಸಲಾಗಿದೆ: ${title.trim()}.", "റിമൈൻഡർ സേവ് ചെയ്തു: ${title.trim()}."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("calendar") || lower.contains("event") || lower.contains("meeting")) {
            val title = extractAfterKeywords(input, listOf("calendar event ", "add event ", "schedule ", "meeting ")) ?: input
            return ActionPlan(
                intent = "create_calendar_event",
                interpretation = "Create calendar event: \"$title\".",
                steps = listOf("Open calendar event insertion or write event"),
                toolCalls = listOf(ToolCallRequest("create_calendar_event", mapOf("title" to title.trim()))),
                assistantReply = "Preparing calendar event: ${title.trim()}.",
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        if (lower.contains("note") || lower.startsWith("write down") || input.contains("నోట్") ||
            input.contains("नोट") || input.contains("குறிப்பு") || input.contains("ಟಿಪ್ಪಣಿ") || input.contains("നോട്ട്")
        ) {
            val content = extractAfterKeywords(input, listOf("create a note ", "create note ", "take a note ", "note ", "write down ", "నోట్ ", "नोट ", "குறிப்பு ", "ಟಿಪ್ಪಣಿ ", "നോട്ട് "))
                ?: input
            return ActionPlan(
                intent = "create_note",
                interpretation = "Save local note on-device.",
                steps = listOf("Persist note in Room database"),
                toolCalls = listOf(ToolCallRequest("create_note", mapOf("title" to content.take(40), "content" to content.trim()))),
                assistantReply = localizedReply(replyLang, "Saved note locally.", "నోట్ లోకల్‌గా సేవ్ చేయబడింది.", "नोट लोकल रूप से सहेजा गया।", "குறிப்பு உள்ளூரில் சேமிக்கப்பட்டது.", "ಟಿಪ್ಪಣಿಯನ್ನು ಸ್ಥಳೀಯವಾಗಿ ಉಳಿಸಲಾಗಿದೆ.", "നോട്ട് ലോക്കലായി സേവ് ചെയ്തു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
            )
        }

        // 12. Files & Downloads & Contacts
        if (lower.contains("download")) {
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

        if (lower.contains("file") || lower.contains("pdf") || lower.contains("document")) {
            val query = extractAfterKeywords(input, listOf("search files for ", "search file ", "find file ", "find pdf ", "open pdf "))
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

        if (lower.contains("contact")) {
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

        if (lower.startsWith("search ") || lower.contains("google ") || lower.contains("search chey") ||
            lower.contains("search karo") || lower.contains("thedu") || lower.contains("huduku") || lower.contains("thedoo")
        ) {
            val query = extractAfterKeywords(input, listOf("search google for ", "search for ", "google ", "search "))
                ?.replace(Regex("(?i)\\b(chey|cheyyi|karo|thedu|huduku|thedoo)\\b"), "")
                ?.trim()
                ?: input
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
                assistantReply = localizedReply(replyLang, "Opening $openAppName.", "$openAppName తెరుస్తున్నాను.", "$openAppName खोल रहा हूँ।", "$openAppName திறக்கிறேன்.", "$openAppName ತೆರೆಯುತ್ತಿದ್ದೇನೆ.", "$openAppName തുറക്കുന്നു."),
                engineUsed = "DETERMINISTIC",
                detectedLanguage = langTag,
                responseLanguage = replyLang.shortBadge
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
        "open", "launch", "search", "volume", "brightness", "reminder", "note",
        "alarm", "settings", "youtube", "whatsapp", "email", "sms", "screen", "scroll", "click", "type"
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
            .replace(Regex("(?i)(youtube|యూట్యూబ్|यूट्यूब|யூடியூப்|ಯೂಟ್ಯೂಬ್|യൂട്യൂബ്)\\s*(lo|mein|par|il|alli|on|for)?"), "")
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
}
