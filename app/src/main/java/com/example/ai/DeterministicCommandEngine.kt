package com.example.ai

import com.example.domain.model.ActionPlan
import com.example.domain.model.ToolCallRequest
import java.net.URLEncoder

class DeterministicCommandEngine {

    fun parse(rawInput: String): ActionPlan? {
        val input = rawInput.trim()
        if (input.isEmpty()) return null
        val lower = input.lowercase()

        // 1. Screen Understanding ("Explain my screen" / "Screen meeda em undi")
        if (lower.contains("explain my screen") ||
            lower.contains("what is on my screen") ||
            lower.contains("analyze screen") ||
            lower.contains("read my screen") ||
            lower.contains("screen meeda") ||
            lower.contains("screen explain")
        ) {
            return ActionPlan(
                intent = "explain_screen",
                interpretation = "Capture and analyze visible screen content locally using on-device OCR/Accessibility.",
                steps = listOf("Check screen capture or accessibility access", "Extract visible text locally", "Summarize & suggest next actions"),
                toolCalls = listOf(ToolCallRequest("analyze_screenshot", mapOf("mode" to "explain"))),
                assistantReply = "Analyzing your current screen locally.",
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
            )
        }

        // 3. Multi-step Naukri job search
        if (lower.contains("naukri") && (lower.contains("search") || lower.contains("job"))) {
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
                engineUsed = "DETERMINISTIC"
            )
        }

        // 4. YouTube search (English + Telugu-English "YouTube lo ... search chey")
        if (lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("lo ") || lower.contains("chudu"))) {
            val query = extractYouTubeQuery(input)
            if (!query.isNullOrBlank()) {
                val url = "https://www.youtube.com/results?search_query=${encode(query)}"
                return ActionPlan(
                    intent = "youtube_search",
                    interpretation = "Open YouTube and search for \"$query\".",
                    steps = listOf("Launch YouTube search for $query"),
                    toolCalls = listOf(ToolCallRequest("open_url", mapOf("url" to url))),
                    assistantReply = "Searching YouTube for $query.",
                    engineUsed = "DETERMINISTIC"
                )
            }
        }

        // 5. WhatsApp message draft (always confirmation-gated)
        if (lower.contains("whatsapp")) {
            val recipient = extractBetween(input, listOf("to "), listOf(" saying ", " message ", " that ", ":"))
            val message = extractAfterKeywords(input, listOf("saying ", "message ", "that "))
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
                    engineUsed = "DETERMINISTIC"
                )
            }
            return ActionPlan(
                intent = "open_app",
                interpretation = "Open WhatsApp.",
                steps = listOf("Launch WhatsApp"),
                toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to "WhatsApp"))),
                assistantReply = "Opening WhatsApp.",
                engineUsed = "DETERMINISTIC"
            )
        }

        // 6. Draft SMS (always confirmation-gated)
        if (lower.contains("sms") || (lower.contains("text ") && lower.contains(" to ")) || lower.contains("message ")) {
            val recipient = extractBetween(input, listOf("to "), listOf(" saying ", " that ", " message ", ":"))
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
                engineUsed = "DETERMINISTIC"
            )
        }

        // 7. Draft Email (always confirmation-gated)
        if (lower.contains("email") || lower.contains("mail ")) {
            if (lower == "open gmail" || lower == "open email" || lower == "gmail open chey") {
                return ActionPlan(
                    intent = "open_app",
                    interpretation = "Open Gmail.",
                    steps = listOf("Launch Gmail"),
                    toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to "Gmail"))),
                    assistantReply = "Opening Gmail.",
                    engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
            )
        }

        // 9. Settings shortcuts
        if (lower.contains("settings") || lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("bluetooth")) {
            val category = when {
                lower.contains("wi-fi") || lower.contains("wifi") -> "wifi"
                lower.contains("bluetooth") -> "bluetooth"
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
                assistantReply = "Opening $category settings.",
                engineUsed = "DETERMINISTIC"
            )
        }

        // 10. Volume & Brightness
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("sound penchu") || lower.contains("sound thagginchu")) {
            val action = when {
                lower.contains("mute") -> "mute"
                lower.contains("increase") || lower.contains("up") || lower.contains("raise") || lower.contains("louder") || lower.contains("penchu") -> "up"
                lower.contains("decrease") || lower.contains("down") || lower.contains("lower") || lower.contains("quieter") || lower.contains("thagginchu") -> "down"
                else -> "up"
            }
            return ActionPlan(
                intent = "adjust_volume",
                interpretation = "Adjust media volume ($action).",
                steps = listOf("Update AudioManager media stream volume"),
                toolCalls = listOf(ToolCallRequest("adjust_volume", mapOf("direction" to action))),
                assistantReply = "Adjusting volume $action.",
                engineUsed = "DETERMINISTIC"
            )
        }

        if (lower.contains("brightness")) {
            val numberMatch = Regex("\\d+").find(lower)?.value?.toIntOrNull()?.coerceIn(5, 100)
            val level = when {
                numberMatch != null -> numberMatch
                lower.contains("max") || lower.contains("full") -> 100
                lower.contains("increase") || lower.contains("up") || lower.contains("higher") -> 80
                lower.contains("decrease") || lower.contains("down") || lower.contains("low") || lower.contains("dim") -> 25
                else -> 60
            }
            return ActionPlan(
                intent = "adjust_brightness",
                interpretation = "Set screen brightness to $level%.",
                steps = listOf("Verify WRITE_SETTINGS permission", "Update screen brightness"),
                toolCalls = listOf(ToolCallRequest("adjust_brightness", mapOf("level" to level.toString()))),
                assistantReply = "Adjusting brightness to $level%.",
                engineUsed = "DETERMINISTIC"
            )
        }

        // 11. Alarm, Reminder, Calendar, Note
        if (lower.contains("alarm")) {
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
                engineUsed = "DETERMINISTIC"
            )
        }

        if (lower.contains("remind") || lower.contains("reminder")) {
            val title = extractAfterKeywords(input, listOf("remind me to ", "create a reminder to ", "create reminder ", "reminder for ", "reminder "))
                ?: input
            return ActionPlan(
                intent = "create_reminder",
                interpretation = "Create local reminder: \"$title\".",
                steps = listOf("Save reminder locally in Room database"),
                toolCalls = listOf(ToolCallRequest("create_reminder", mapOf("title" to title.trim()))),
                assistantReply = "Created reminder: ${title.trim()}.",
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
            )
        }

        if (lower.contains("note") || lower.startsWith("write down")) {
            val content = extractAfterKeywords(input, listOf("create a note ", "create note ", "take a note ", "note ", "write down "))
                ?: input
            return ActionPlan(
                intent = "create_note",
                interpretation = "Save local note on-device.",
                steps = listOf("Persist note in Room database"),
                toolCalls = listOf(ToolCallRequest("create_note", mapOf("title" to content.take(40), "content" to content.trim()))),
                assistantReply = "Saved note locally.",
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
            )
        }

        if (lower.startsWith("search ") || lower.contains("google ") || lower.contains("search chey")) {
            val query = extractAfterKeywords(input, listOf("search google for ", "search for ", "google ", "search "))
                ?.replace("chey", "", ignoreCase = true)
                ?.trim()
                ?: input
            return ActionPlan(
                intent = "web_search",
                interpretation = "Search web for \"$query\".",
                steps = listOf("Open browser search for $query"),
                toolCalls = listOf(ToolCallRequest("web_search", mapOf("query" to query))),
                assistantReply = "Searching the web for $query.",
                engineUsed = "DETERMINISTIC"
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
                engineUsed = "DETERMINISTIC"
            )
        }

        // 15. Open App (English + Telugu-English "Chrome open chey" / "YouTube teruvu")
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ") ||
            lower.endsWith("open chey") || lower.endsWith("open cheyyi") || lower.endsWith("kholo")
        ) {
            val appName = when {
                lower.startsWith("open ") -> input.substring(5).trim()
                lower.startsWith("launch ") -> input.substring(7).trim()
                lower.startsWith("start ") -> input.substring(6).trim()
                lower.endsWith("open chey") -> input.dropLast(9).trim()
                lower.endsWith("open cheyyi") -> input.dropLast(11).trim()
                lower.endsWith("kholo") -> input.dropLast(5).trim()
                else -> input
            }
            if (appName.isNotBlank()) {
                return ActionPlan(
                    intent = "open_app",
                    interpretation = "Launch installed application \"$appName\".",
                    steps = listOf("Find matching launcher package", "Start activity"),
                    toolCalls = listOf(ToolCallRequest("open_app", mapOf("app_name" to appName))),
                    assistantReply = "Opening $appName.",
                    engineUsed = "DETERMINISTIC"
                )
            }
        }

        return null
    }

    private fun extractYouTubeQuery(input: String): String? {
        val cleaned = input
            .replace(Regex("(?i)youtube\\s*(lo|mein|on|for)?"), "")
            .replace(Regex("(?i)\\b(open|and|search|for|chey|cheyyi|chudu|play|karo)\\b"), " ")
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
