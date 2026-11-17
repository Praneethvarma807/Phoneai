package com.example.tools

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.automation.CommanderAccessibilityService
import com.example.automation.DocumentAccessManager
import com.example.automation.ReminderScheduler
import com.example.automation.ScreenUnderstandingManager
import com.example.data.local.LocalNoteDao
import com.example.data.local.LocalNoteEntity
import com.example.data.local.LocalReminderDao
import com.example.data.local.LocalReminderEntity
import com.example.domain.model.ActionCategory
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.ToolResult
import com.example.domain.model.ToolSchemaInfo
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ContactLookupResult {
    data class Matched(val displayName: String, val digits: String, val dialString: String) : ContactLookupResult
    data class Ambiguous(val names: List<String>) : ContactLookupResult
    data object NotFound : ContactLookupResult
    data object PermissionMissing : ContactLookupResult
}

internal suspend fun resolveContactPhone(
    context: Context,
    rawQuery: String
): ContactLookupResult = withContext(Dispatchers.IO) {
    val trimmed = rawQuery.trim()
    if (trimmed.isEmpty()) return@withContext ContactLookupResult.NotFound

    if (!trimmed.any { it.isLetter() }) {
        val dial = trimmed.filter { it.isDigit() || it == '+' }
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length >= 7) {
            return@withContext ContactLookupResult.Matched(trimmed, digits, dial)
        }
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return@withContext ContactLookupResult.PermissionMissing
    }

    val candidates = runCatching {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val rows = mutableListOf<Triple<String, String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$trimmed%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 25) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val rawNum = cursor.getString(1)?.trim().orEmpty()
                val dial = rawNum.filter { it.isDigit() || it == '+' }
                val digits = rawNum.filter { it.isDigit() }
                if (digits.length >= 7) {
                    rows.add(Triple(name.ifBlank { trimmed }, digits, dial))
                }
            }
        }
        rows.distinctBy { it.first.lowercase() to it.second }
    }.getOrDefault(emptyList())

    if (candidates.isEmpty()) return@withContext ContactLookupResult.NotFound

    val exactMatches = candidates.filter { it.first.equals(trimmed, ignoreCase = true) }
    if (exactMatches.size == 1) {
        val m = exactMatches.first()
        return@withContext ContactLookupResult.Matched(m.first, m.second, m.third)
    }
    if (candidates.size == 1) {
        val m = candidates.first()
        return@withContext ContactLookupResult.Matched(m.first, m.second, m.third)
    }

    val prefixMatches = candidates.filter { it.first.startsWith(trimmed, ignoreCase = true) }
    if (prefixMatches.size == 1) {
        val m = prefixMatches.first()
        return@withContext ContactLookupResult.Matched(m.first, m.second, m.third)
    }

    ContactLookupResult.Ambiguous(candidates.map { it.first }.distinct().take(4))
}

object PhoneToolsFactory {
    fun createAll(
        context: Context,
        noteDao: LocalNoteDao,
        reminderDao: LocalReminderDao,
        screenUnderstandingManager: ScreenUnderstandingManager,
        reminderScheduler: ReminderScheduler = ReminderScheduler(context),
        documentAccessManager: DocumentAccessManager? = null,
        connectivityMonitor: com.example.network.ConnectivityMonitor? = null,
        memoryRepository: com.example.memory.PersonalMemoryRepository? = null,
        automationEngine: com.example.automation.AutomationEngine? = null
    ): List<CommanderTool> = buildList {
        addAll(
            listOf(
                OpenAppTool(context),
                CheckAppInstalledTool(context),
                OpenUrlTool(context),
                WebSearchTool(context),
                BrowserNavigateTool(context),
                BrowserClickTool(),
                BrowserTypeTool(),
                BrowserScrollTool(),
                GetScreenTextTool(),
                AnalyzeScreenshotTool(screenUnderstandingManager),
                AdjustBrightnessTool(context),
                AdjustVolumeTool(context),
                OpenSettingsTool(context),
                CreateReminderTool(reminderDao, reminderScheduler),
                CreateCalendarEventTool(context),
                SearchContactsTool(context),
                ComposeSmsTool(context),
                ComposeEmailTool(context),
                SendWhatsAppDraftTool(context),
                SearchFilesTool(context, documentAccessManager),
                CreateNoteTool(noteDao),
                SetAlarmTool(context),
                OpenDownloadsTool(context),
                ToggleFlashlightTool(context),
                SetTimerTool(context),
                GetBatteryStatusTool(context),
                GetDeviceInfoTool(context),
                GetNetworkStatusTool(context, connectivityMonitor),
                DialPhoneNumberTool(context),
                SummarizeNotificationsTool(context)
            )
        )
        if (memoryRepository != null) {
            add(SaveMemoryTool(memoryRepository))
            add(RecallMemoryTool(memoryRepository))
            add(ForgetMemoryTool(memoryRepository))
        }
        if (automationEngine != null) {
            add(RunRoutineTool(automationEngine))
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun findAppPackageByName(context: Context, query: String): Pair<String, String>? {
    val pm = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcherIntent, 0)
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null

    // Exact or alias matching first
    val aliases = mapOf(
        "chrome" to listOf("chrome", "google chrome", "com.android.chrome", "com.chrome.beta", "com.chrome.dev"),
        "youtube" to listOf("youtube", "com.google.android.youtube"),
        "gmail" to listOf("gmail", "com.google.android.gm"),
        "whatsapp" to listOf("whatsapp", "whatsapp business", "com.whatsapp", "com.whatsapp.w4b"),
        "play store" to listOf("play store", "google play store", "com.android.vending"),
        "settings" to listOf("settings", "com.android.settings"),
        "camera" to listOf("camera"),
        "maps" to listOf("maps", "google maps", "com.google.android.apps.maps"),
        "calculator" to listOf("calculator", "com.google.android.calculator", "com.android.calculator2"),
        "clock" to listOf("clock", "deskclock", "com.google.android.deskclock", "com.android.deskclock"),
        "phone" to listOf("phone", "dialer", "com.google.android.dialer", "com.android.dialer"),
        "messages" to listOf("messages", "com.google.android.apps.messaging", "com.android.messaging"),
        "contacts" to listOf("contacts", "com.google.android.contacts", "com.android.contacts"),
        "photos" to listOf("photos", "gallery", "com.google.android.apps.photos"),
        "gallery" to listOf("gallery", "photos", "com.google.android.apps.photos"),
        "calendar" to listOf("calendar", "com.google.android.calendar"),
        "files" to listOf("files", "files by google", "com.google.android.apps.nbu.files")
    )

    val candidateTokens = aliases[q] ?: listOf(q)
    for (token in candidateTokens) {
        val exact = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase()
            val pkg = it.activityInfo.packageName.lowercase()
            label == token || pkg == token
        }
        if (exact != null) {
            return exact.activityInfo.packageName to exact.loadLabel(pm).toString()
        }
    }

    val startsWithMatch = apps.firstOrNull {
        val label = it.loadLabel(pm).toString().lowercase()
        label.startsWith(q)
    }
    if (startsWithMatch != null) {
        return startsWithMatch.activityInfo.packageName to startsWithMatch.loadLabel(pm).toString()
    }

    val partial = apps.firstOrNull {
        val label = it.loadLabel(pm).toString().lowercase()
        val pkg = it.activityInfo.packageName.lowercase()
        label.contains(q) || pkg.contains(q)
    }
    return partial?.let { it.activityInfo.packageName to it.loadLabel(pm).toString() }
}

class OpenAppTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.APP_LAUNCH
    override val schema = ToolSchemaInfo(
        name = "open_app",
        description = "Launch an installed Android application by display name or package name.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("app_name" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["app_name"].isNullOrBlank()) "Missing 'app_name' parameter." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val appName = arguments.getValue("app_name").trim()
        val match = findAppPackageByName(context, appName)
        if (match == null && appName.equals("settings", ignoreCase = true)) {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (settingsIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(settingsIntent)
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = "Launched Settings.",
                    verificationNote = "Settings.ACTION_SETTINGS started",
                    data = mapOf("package" to "com.android.settings", "label" to "Settings"),
                    category = category
                )
            }
        }
        val (pkg, resolvedLabel) = match
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Application '$appName' is not installed or has no launcher activity.",
                verificationNote = "PackageManager launcher query returned no match",
                category = category
            )
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.FAILED,
            message = "Unable to create launch intent for $resolvedLabel ($pkg).",
            verificationNote = "getLaunchIntentForPackage returned null",
            category = category
        )
        context.startActivity(launchIntent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Launched $resolvedLabel.",
            verificationNote = "Verified package $pkg resolved and started",
            data = mapOf("package" to pkg, "label" to resolvedLabel),
            category = category
        )
    }
}

class CheckAppInstalledTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.APP_LAUNCH
    override val schema = ToolSchemaInfo(
        name = "check_app_installed",
        description = "Check whether a specific app is installed on the device.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("app_name" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["app_name"].isNullOrBlank()) "Missing 'app_name' parameter." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val appName = arguments.getValue("app_name").trim()
        val match = findAppPackageByName(context, appName)
        return if (match != null) {
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = "${match.second} is installed (${match.first}).",
                verificationNote = "Verified via PackageManager",
                data = mapOf("installed" to "true", "package" to match.first),
                category = category
            )
        } else {
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = "'$appName' was not found among launcher apps.",
                verificationNote = "Verified not present in launcher query",
                data = mapOf("installed" to "false"),
                category = category
            )
        }
    }
}

class OpenUrlTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.BROWSER
    override val schema = ToolSchemaInfo(
        name = "open_url",
        description = "Open an HTTPS/HTTP URL in the device browser.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("url" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["url"].isNullOrBlank()) "Missing 'url' parameter." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val raw = arguments.getValue("url").trim()
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val online = context.isOnline()
        val canResolve = intent.resolveActivity(context.packageManager) != null
        if (!canResolve) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No browser app available to open $url.",
                verificationNote = "ACTION_VIEW could not resolve",
                category = category
            )
        }
        context.startActivity(intent)
        val offlineNote = if (!online) " Note: Device appears offline; page requires internet." else ""
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened $url in browser.$offlineNote",
            verificationNote = "Verified browser activity started",
            data = mapOf("url" to url, "online" to online.toString()),
            category = category
        )
    }
}

class WebSearchTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SEARCH
    override val schema = ToolSchemaInfo(
        name = "web_search",
        description = "Perform a public web search in the user's browser.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("query" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["query"].isNullOrBlank()) "Missing 'query' parameter." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments.getValue("query").trim()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$encoded"
        val online = context.isOnline()
        if (!online) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Live web search requires internet connectivity. Device is currently offline.",
                verificationNote = "ConnectivityManager reports offline state",
                category = category
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No web browser app available to run search.",
                verificationNote = "Web search ACTION_VIEW unresolved",
                category = category
            )
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = "Searching Google for \"$query\".",
                verificationNote = "Verified browser search intent launched",
                data = mapOf("url" to url),
                category = category
            )
        }.getOrElse { err ->
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Unable to open browser search: ${err.message ?: "activity unavailable"}",
                verificationNote = "startActivity threw exception",
                category = category
            )
        }
    }
}

class BrowserNavigateTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.BROWSER
    override val schema = ToolSchemaInfo(
        name = "browser_navigate",
        description = "Navigate to a URL, go back, go to Home screen, or capture system screenshot.",
        requiredPermissions = listOf("AccessibilityService (for back/screenshot)"),
        requiresConfirmation = false,
        parameters = mapOf("action" to "back | home | screenshot | url", "url" to "optional URL"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val action = arguments["action"]?.lowercase() ?: "url"
        if (action == "back") {
            val service = CommanderAccessibilityService.activeService.value
                ?: return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.PERMISSION_REQUIRED,
                    message = "Enable AI Phone Commander Accessibility Service to navigate backward.",
                    verificationNote = "AccessibilityService inactive",
                    requiresAccessibility = true,
                    category = category
                )
            val ok = service.navigateBack()
            return ToolResult(
                toolName = schema.name,
                status = if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                message = if (ok) "Navigated back." else "Back navigation failed.",
                verificationNote = "Accessibility GLOBAL_ACTION_BACK=$ok",
                category = category
            )
        }
        if (action == "home") {
            val service = CommanderAccessibilityService.activeService.value
            if (service != null && service.navigateHome()) {
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = "Navigated to home screen.",
                    verificationNote = "Accessibility GLOBAL_ACTION_HOME=true",
                    category = ActionCategory.SYSTEM
                )
            }
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (homeIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(homeIntent)
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = "Navigated to home screen.",
                    verificationNote = "CATEGORY_HOME launcher intent started",
                    category = ActionCategory.SYSTEM
                )
            }
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Unable to navigate to home screen on this device.",
                verificationNote = "No HOME launcher resolved",
                category = ActionCategory.SYSTEM
            )
        }
        if (action == "screenshot") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.UNSUPPORTED,
                    message = "System screenshot global action requires Android 9 (API 28) or newer.",
                    verificationNote = "SDK=${Build.VERSION.SDK_INT} < 28",
                    category = ActionCategory.SYSTEM
                )
            }
            val service = CommanderAccessibilityService.activeService.value
                ?: return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.PERMISSION_REQUIRED,
                    message = "Enable AI Commander Accessibility Service to take a system screenshot.",
                    verificationNote = "AccessibilityService inactive for GLOBAL_ACTION_TAKE_SCREENSHOT",
                    requiresAccessibility = true,
                    category = ActionCategory.SYSTEM
                )
            val ok = service.takeSystemScreenshot()
            return ToolResult(
                toolName = schema.name,
                status = if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                message = if (ok) "Screenshot captured." else "System screenshot action failed.",
                verificationNote = "Accessibility GLOBAL_ACTION_TAKE_SCREENSHOT=$ok",
                category = ActionCategory.SYSTEM
            )
        }
        val url = arguments["url"].orEmpty()
        return OpenUrlTool(context).execute(mapOf("url" to url))
    }
}

class BrowserClickTool : CommanderTool {
    override val category = ActionCategory.BROWSER
    override val schema = ToolSchemaInfo(
        name = "browser_click",
        description = "Click a visible button, link, or UI element matching target text via Accessibility Service.",
        requiredPermissions = listOf("AccessibilityService"),
        requiresConfirmation = false,
        parameters = mapOf("target" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["target"].isNullOrBlank()) "Missing 'target' text to click." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val service = CommanderAccessibilityService.activeService.value
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Accessibility Service is required to click UI elements.",
                verificationNote = "AccessibilityService disconnected",
                requiresAccessibility = true,
                category = category
            )
        val target = arguments.getValue("target")
        val clicked = service.clickNodeByText(target)
        return ToolResult(
            toolName = schema.name,
            status = if (clicked) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            message = if (clicked) "Clicked element matching \"$target\"." else "Could not find clickable element matching \"$target\".",
            verificationNote = "ACTION_CLICK result=$clicked",
            category = category
        )
    }
}

class BrowserTypeTool : CommanderTool {
    override val category = ActionCategory.BROWSER
    override val schema = ToolSchemaInfo(
        name = "browser_type",
        description = "Enter text into an editable field via Accessibility Service.",
        requiredPermissions = listOf("AccessibilityService"),
        requiresConfirmation = false,
        parameters = mapOf("text" to "string (required)", "target" to "optional field hint"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["text"].isNullOrBlank()) "Missing 'text' to enter." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val service = CommanderAccessibilityService.activeService.value
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Accessibility Service is required to enter text in external apps/browsers.",
                verificationNote = "AccessibilityService disconnected",
                requiresAccessibility = true,
                category = category
            )
        val text = arguments.getValue("text")
        val target = arguments["target"]
        val ok = service.typeTextInField(target, text)
        return ToolResult(
            toolName = schema.name,
            status = if (ok) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            message = if (ok) "Entered text into field." else "No editable field found on current screen.",
            verificationNote = "ACTION_SET_TEXT result=$ok",
            category = category
        )
    }
}

class BrowserScrollTool : CommanderTool {
    override val category = ActionCategory.BROWSER
    override val schema = ToolSchemaInfo(
        name = "browser_scroll",
        description = "Scroll the active browser or app window up or down.",
        requiredPermissions = listOf("AccessibilityService"),
        requiresConfirmation = false,
        parameters = mapOf("direction" to "down | up"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val service = CommanderAccessibilityService.activeService.value
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Accessibility Service is required to scroll active windows.",
                verificationNote = "AccessibilityService disconnected",
                requiresAccessibility = true,
                category = category
            )
        val direction = arguments["direction"] ?: "down"
        val scrolled = service.scrollWindow(direction)
        return ToolResult(
            toolName = schema.name,
            status = if (scrolled) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            message = if (scrolled) "Scrolled $direction." else "Active window has no scrollable container.",
            verificationNote = "Scroll $direction=$scrolled",
            category = category
        )
    }
}

class GetScreenTextTool : CommanderTool {
    override val category = ActionCategory.VISION
    override val schema = ToolSchemaInfo(
        name = "get_screen_text",
        description = "Read visible text and interactive elements on the current screen via Accessibility Service.",
        requiredPermissions = listOf("AccessibilityService"),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val service = CommanderAccessibilityService.activeService.value
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Accessibility Service is off. Enable it in Permissions to read screen elements.",
                verificationNote = "AccessibilityService disconnected",
                requiresAccessibility = true,
                category = category
            )
        val snapshot = service.captureScreenSnapshot()
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Active window returned no readable nodes.",
                verificationNote = "rootInActiveWindow was null",
                category = category
            )
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Read ${snapshot.visibleLines.size} visible text items from ${snapshot.packageName}.",
            verificationNote = "Extracted ${snapshot.visibleLines.size} lines locally",
            data = mapOf(
                "package" to snapshot.packageName,
                "text" to snapshot.combinedText.take(1500),
                "clickables" to snapshot.clickableTargets.take(10).joinToString(", ")
            ),
            category = category
        )
    }
}

class AnalyzeScreenshotTool(
    private val screenUnderstandingManager: ScreenUnderstandingManager
) : CommanderTool {
    override val category = ActionCategory.VISION
    override val schema = ToolSchemaInfo(
        name = "analyze_screenshot",
        description = "Capture screen locally via MediaProjection + ML Kit OCR and explain visible content without cloud upload.",
        requiredPermissions = listOf("MediaProjection OR AccessibilityService"),
        requiresConfirmation = false,
        parameters = mapOf("mode" to "explain | ocr"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val hasAccessibility = CommanderAccessibilityService.isConnected()
        if (!screenUnderstandingManager.hasProjectionGrant() && !hasAccessibility) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Grant Screen Capture permission (or enable Accessibility) to analyze your screen locally.",
                verificationNote = "Awaiting MediaProjection consent",
                requiresScreenCapture = true,
                category = category
            )
        }
        val reportResult = screenUnderstandingManager.analyzeCurrentScreen()
        return reportResult.fold(
            onSuccess = { report ->
                ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = report.explanation,
                    verificationNote = "Analyzed locally via ${report.sourceUsed}",
                    data = mapOf(
                        "source" to report.sourceUsed,
                        "extracted_text" to report.extractedText,
                        "suggestions" to report.suggestedActions.joinToString(" | ")
                    ),
                    category = category
                )
            },
            onFailure = { err ->
                ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.FAILED,
                    message = err.message ?: "Screen analysis failed.",
                    verificationNote = "Local OCR / capture returned empty",
                    category = category
                )
            }
        )
    }
}

class AdjustBrightnessTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SETTINGS
    override val schema = ToolSchemaInfo(
        name = "adjust_brightness",
        description = "Adjust system screen brightness (0-100%) when WRITE_SETTINGS is granted.",
        requiredPermissions = listOf("android.permission.WRITE_SETTINGS"),
        requiresConfirmation = false,
        parameters = mapOf("level" to "integer 5..100"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        if (!Settings.System.canWrite(context)) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Modify System Settings (WRITE_SETTINGS) permission is required to adjust brightness.",
                verificationNote = "Settings.System.canWrite returned false",
                requiresWriteSettings = true,
                category = category
            )
        }
        val pct = (arguments["level"]?.toIntOrNull() ?: 60).coerceIn(5, 100)
        val value = (pct * 255) / 100
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        val readback = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Screen brightness set to $pct%.",
            verificationNote = "Readback SCREEN_BRIGHTNESS=$readback/255",
            data = mapOf("brightness_pct" to pct.toString(), "raw" to readback.toString()),
            category = category
        )
    }
}

class AdjustVolumeTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SETTINGS
    override val schema = ToolSchemaInfo(
        name = "adjust_volume",
        description = "Adjust media audio volume (up, down, mute).",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("direction" to "up | down | mute"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = arguments["direction"]?.lowercase() ?: "up"
        when (direction) {
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            else -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Media volume adjusted ($direction): $current/$max.",
            verificationNote = "Verified AudioManager stream level $current/$max",
            data = mapOf("current" to current.toString(), "max" to max.toString()),
            category = category
        )
    }
}

class OpenSettingsTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SETTINGS
    override val schema = ToolSchemaInfo(
        name = "open_settings",
        description = "Open specific Android system settings screens.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("section" to "wifi | bluetooth | display | sound | location | battery | accessibility | apps | main"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val section = arguments["section"]?.lowercase() ?: "main"
        val action = when (section) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = if (intent.resolveActivity(context.packageManager) != null) intent else fallback
        context.startActivity(resolved)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened $section settings.",
            verificationNote = "Settings activity started (${resolved.action})",
            category = category
        )
    }
}

class CreateReminderTool(
    private val reminderDao: LocalReminderDao,
    private val reminderScheduler: ReminderScheduler? = null
) : CommanderTool {
    override val category = ActionCategory.CALENDAR
    override val schema = ToolSchemaInfo(
        name = "create_reminder",
        description = "Create, save, and schedule a local reminder with AlarmManager/WorkManager notifications.",
        requiredPermissions = listOf("POST_NOTIFICATIONS (API 33+)"),
        requiresConfirmation = false,
        parameters = mapOf(
            "title" to "string (required)",
            "delay_minutes" to "optional integer",
            "trigger_at_millis" to "optional epoch millis",
            "notes" to "optional string"
        ),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["title"].isNullOrBlank()) "Missing reminder 'title'." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val title = arguments.getValue("title").trim()
        val notes = arguments["notes"]?.trim().orEmpty()
        val explicitTrigger = arguments["trigger_at_millis"]?.toLongOrNull()
        val delayMin = (arguments["delay_minutes"]?.toLongOrNull() ?: 60L).coerceAtLeast(1L)
        val triggerAt = explicitTrigger?.takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + delayMin * 60_000L)

        val entity = LocalReminderEntity(title = title, triggerAtMillis = triggerAt, notes = notes)
        val id = reminderDao.insert(entity)
        val persisted = entity.copy(id = id)
        val scheduleResult = reminderScheduler?.scheduleReminder(persisted)

        val formattedTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(triggerAt))
        val notifNote = if (scheduleResult != null && !scheduleResult.notificationPermissionGranted) {
            " Allow Notifications in Settings to receive alert banners."
        } else {
            ""
        }
        val mechLabel = scheduleResult?.mechanism?.label ?: "Room Only"

        ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Scheduled reminder #$id: \"$title\" for $formattedTime.$notifNote",
            verificationNote = "Persisted in Room (id=$id) & scheduled via $mechLabel",
            data = buildMap {
                put("reminder_id", id.toString())
                put("title", title)
                put("trigger_at_millis", triggerAt.toString())
                put("mechanism", mechLabel)
                if (scheduleResult != null) {
                    put("notifications_granted", scheduleResult.notificationPermissionGranted.toString())
                    put("exact_alarm_granted", scheduleResult.exactAlarmPermissionGranted.toString())
                }
            },
            category = category
        )
    }
}

class CreateCalendarEventTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.CALENDAR
    override val schema = ToolSchemaInfo(
        name = "create_calendar_event",
        description = "Create a calendar event using Android Calendar Intent.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("title" to "string (required)", "location" to "optional string"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["title"].isNullOrBlank()) "Missing event 'title'." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val title = arguments.getValue("title").trim()
        val location = arguments["location"].orEmpty()
        val start = System.currentTimeMillis() + 3600_000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No Calendar app available to create event.",
                verificationNote = "Calendar Intent unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened Calendar editor for \"$title\".",
            verificationNote = "CalendarContract.Events insert intent launched",
            category = category
        )
    }
}

class SearchContactsTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.MESSAGING
    override val schema = ToolSchemaInfo(
        name = "search_contacts",
        description = "Search local device contacts by name.",
        requiredPermissions = listOf(Manifest.permission.READ_CONTACTS),
        requiresConfirmation = false,
        parameters = mapOf("query" to "string"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) {
            return@withContext ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Contacts permission is required to look up contacts.",
                verificationNote = "READ_CONTACTS not granted",
                missingPermissions = listOf(Manifest.permission.READ_CONTACTS),
                category = category
            )
        }
        val query = arguments["query"].orEmpty().trim()
        val matches = mutableListOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = if (query.isNotBlank()) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null
        val selArgs = if (query.isNotBlank()) arrayOf("%$query%") else null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && matches.size < 8) {
                val name = cursor.getString(0) ?: "Unknown"
                val number = cursor.getString(1) ?: ""
                matches.add("$name ($number)")
            }
        }
        ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = if (matches.isEmpty()) "No contacts matched '$query'." else "Found ${matches.size} contact(s): ${matches.joinToString("; ")}",
            verificationNote = "Queried ContactsContract locally",
            data = mapOf("matches" to matches.joinToString("\n")),
            category = category
        )
    }
}

class ComposeSmsTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.MESSAGING
    override val schema = ToolSchemaInfo(
        name = "compose_sms",
        description = "Draft an SMS message (opens system SMS composer; never sends automatically).",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("recipient" to "phone or name", "body" to "message text"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val rawRecipient = arguments["recipient"].orEmpty().trim()
        val body = arguments["body"].orEmpty().trim()
        if (rawRecipient.isBlank() && body.isBlank()) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Provide a recipient or SMS message body before drafting.",
                verificationNote = "Empty SMS arguments",
                category = category
            )
        }

        val resolved = resolveContactPhone(context, rawRecipient)
        if (resolved is ContactLookupResult.Ambiguous) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Multiple contacts match \"$rawRecipient\" (${resolved.names.joinToString(", ")}). Please use the full name or phone number.",
                verificationNote = "Ambiguous SMS contact match",
                category = category
            )
        }

        val targetAddress = when (resolved) {
            is ContactLookupResult.Matched -> resolved.dialString
            else -> rawRecipient
        }
        val targetLabel = when (resolved) {
            is ContactLookupResult.Matched -> resolved.displayName
            else -> rawRecipient.ifBlank { "selected contact" }
        }

        val uri = Uri.parse("smsto:${Uri.encode(targetAddress)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No SMS app found on device.",
                verificationNote = "smsto intent unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened SMS draft for $targetLabel.",
            verificationNote = "Draft opened in SMS app (user sends manually)",
            data = mapOf("recipient_address" to targetAddress),
            category = category
        )
    }
}

class ComposeEmailTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.MESSAGING
    override val schema = ToolSchemaInfo(
        name = "compose_email",
        description = "Draft an email in the user's mail client (requires confirmation).",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("to" to "email address", "subject" to "subject line", "body" to "email body"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val to = arguments["to"].orEmpty().trim()
        val subject = arguments["subject"].orEmpty().trim()
        val body = arguments["body"].orEmpty().trim()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No email app found on device.",
                verificationNote = "mailto intent unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened email draft${if (to.isNotBlank()) " to $to" else ""}.",
            verificationNote = "Email composer launched",
            category = category
        )
    }
}

class SendWhatsAppDraftTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.MESSAGING
    override val schema = ToolSchemaInfo(
        name = "send_whatsapp_draft",
        description = "Prepare a WhatsApp message draft (opens WhatsApp chat or composer; requires confirmation).",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("recipient" to "optional name/number", "message" to "message text"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val recipient = arguments["recipient"].orEmpty().trim()
        val msg = arguments["message"].orEmpty().trim()
        if (recipient.isBlank() && msg.isBlank()) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Provide a WhatsApp recipient or message text before drafting.",
                verificationNote = "Empty WhatsApp draft arguments",
                category = category
            )
        }
        if (recipient.isNotBlank() && isMalformedPhoneInput(recipient)) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Invalid WhatsApp recipient \"$recipient\". Enter a full phone number (at least 7 digits) or a contact name.",
                verificationNote = "Malformed numeric/punctuation recipient rejected",
                category = category
            )
        }
        val whatsappPkg = resolveInstalledWhatsAppPackage()
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "WhatsApp is not installed on this device.",
                verificationNote = "Neither com.whatsapp nor com.whatsapp.w4b resolved",
                category = category
            )

        val lookup = resolveContactPhone(context, recipient)
        if (lookup is ContactLookupResult.Ambiguous) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Multiple contacts match \"$recipient\" (${lookup.names.joinToString(", ")}). Say the full contact name or phone number.",
                verificationNote = "Ambiguous WhatsApp contact match",
                category = category
            )
        }

        val resolvedDigits = when (lookup) {
            is ContactLookupResult.Matched -> lookup.digits
            else -> null
        }
        if (resolvedDigits != null) {
            val encodedText = Uri.encode(msg)
            val waUri = if (msg.isNotBlank()) {
                Uri.parse("https://wa.me/$resolvedDigits?text=$encodedText")
            } else {
                Uri.parse("https://wa.me/$resolvedDigits")
            }
            val directIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                setPackage(whatsappPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (directIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(directIntent)
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = "Opened WhatsApp draft for ${recipient.ifBlank { resolvedDigits }}.",
                    verificationNote = "WhatsApp direct chat URI launched ($whatsappPkg)",
                    data = mapOf(
                        "package" to whatsappPkg,
                        "recipient_digits" to resolvedDigits,
                        "mode" to "direct_chat"
                    ),
                    category = category
                )
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(whatsappPkg)
            putExtra(Intent.EXTRA_TEXT, msg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (shareIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(shareIntent)
            val message = if (recipient.isNotBlank()) {
                "Opened WhatsApp with prepared message draft (select \"$recipient\" in WhatsApp, or provide a phone number / Contacts permission for direct targeting)."
            } else {
                "Opened WhatsApp with prepared message draft."
            }
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = message,
                verificationNote = "WhatsApp ACTION_SEND launched ($whatsappPkg)",
                data = mapOf(
                    "package" to whatsappPkg,
                    "mode" to "share_composer"
                ),
                category = category
            )
        }

        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.FAILED,
            message = "Unable to open WhatsApp composer on this device.",
            verificationNote = "WhatsApp intent unresolved for $whatsappPkg",
            category = category
        )
    }

    internal fun resolveInstalledWhatsAppPackage(): String? {
        val pm = context.packageManager
        for (pkg in WHATSAPP_PACKAGES) {
            val probeSend = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
            }
            if (probeSend.resolveActivity(pm) != null) return pkg

            val probeView = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/1234567")).apply {
                setPackage(pkg)
            }
            if (probeView.resolveActivity(pm) != null) return pkg
        }
        return null
    }

    internal suspend fun resolveRecipientPhoneDigits(recipient: String): String? =
        when (val res = resolveContactPhone(context, recipient)) {
            is ContactLookupResult.Matched -> res.digits
            else -> null
        }

    companion object {
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        internal fun extractPhoneDigitsIfNumeric(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.isLetter() }) return null
            val digits = trimmed.filter { it.isDigit() }
            return digits.takeIf { it.length >= 7 }
        }

        internal fun isMalformedPhoneInput(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.any { it.isLetter() }) return false
            val digits = trimmed.count { it.isDigit() }
            return digits < 7
        }
    }
}

class SearchFilesTool(
    private val context: Context,
    private val documentAccessManager: DocumentAccessManager? = null
) : CommanderTool {
    override val category = ActionCategory.DOCUMENT
    override val schema = ToolSchemaInfo(
        name = "search_files",
        description = "Search SAF-authorized documents, internal files, and Downloads without broad storage permissions.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("query" to "optional filename keyword"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = arguments["query"].orEmpty().trim().lowercase()
        val found = mutableListOf<String>()

        // 1. Search persisted Storage Access Framework (SAF) files and folders
        val safResult = documentAccessManager?.searchAuthorizedDocuments(query)
        safResult?.matches?.forEach { match ->
            val kb = if (match.sizeBytes >= 0) "${(match.sizeBytes / 1024).coerceAtLeast(1)} KB, " else ""
            found.add("${match.displayName} (${kb}${match.sourceLabel})")
        }

        // 2. Check app-local exported/created files
        context.filesDir.listFiles()?.forEach { file ->
            if (file.isFile && (query.isBlank() || file.name.lowercase().contains(query))) {
                found.add("${file.name} (${file.length() / 1024} KB, internal)")
            }
        }

        // 3. Query MediaStore Downloads (accessible without broad READ_EXTERNAL_STORAGE on API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && found.size < 16) {
                        val name = cursor.getString(0) ?: continue
                        val size = cursor.getLong(1)
                        if (query.isBlank() || name.lowercase().contains(query)) {
                            found.add("$name (${size / 1024} KB, Downloads)")
                        }
                    }
                }
            }
        }

        val revokedNote = if (safResult != null && safResult.revokedOrMissing.isNotEmpty()) {
            " (${safResult.revokedOrMissing.size} previously authorized item(s) became unavailable or revoked.)"
        } else {
            ""
        }

        val safCount = safResult?.matches?.size ?: 0
        val message = if (found.isEmpty()) {
            "No accessible documents matched '$query'.$revokedNote Use Open PDF / Authorize Folder in Tools to grant zero-permission SAF access."
        } else {
            "Found ${found.size} document(s): ${found.joinToString(", ")}$revokedNote"
        }

        ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = message,
            verificationNote = "Queried SAF roots ($safCount matches), internal files & MediaStore Downloads",
            data = buildMap {
                put("files", found.joinToString("\n"))
                put("saf_matches", safCount.toString())
                if (safResult != null && safResult.matches.isNotEmpty()) {
                    put("first_saf_uri", safResult.matches.first().uriString)
                }
            },
            category = category
        )
    }
}

class CreateNoteTool(private val noteDao: LocalNoteDao) : CommanderTool {
    override val category = ActionCategory.DOCUMENT
    override val schema = ToolSchemaInfo(
        name = "create_note",
        description = "Create and save a private on-device note in Room database.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("title" to "string", "content" to "string (required)"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? =
        if (arguments["content"].isNullOrBlank() && arguments["title"].isNullOrBlank()) "Note content cannot be empty." else null

    override suspend fun execute(arguments: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val content = arguments["content"].orEmpty().ifBlank { arguments["title"].orEmpty() }.trim()
        val title = arguments["title"].orEmpty().ifBlank { content.take(40) }.trim()
        val id = noteDao.insert(LocalNoteEntity(title = title, content = content))
        ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Saved note \"$title\" locally.",
            verificationNote = "Persisted in Room local_notes table (id=$id)",
            data = mapOf("note_id" to id.toString(), "title" to title),
            category = category
        )
    }
}

class SetAlarmTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.CALENDAR
    override val schema = ToolSchemaInfo(
        name = "set_alarm",
        description = "Set an alarm using the system AlarmClock API.",
        requiredPermissions = listOf("com.android.alarm.permission.SET_ALARM"),
        requiresConfirmation = false,
        parameters = mapOf("hour" to "0..23", "minute" to "0..59", "label" to "optional label"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val hour = (arguments["hour"]?.toIntOrNull() ?: 7).coerceIn(0, 23)
        val minute = (arguments["minute"]?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val label = arguments["label"] ?: "AI Commander Alarm"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No Clock app found to set alarm.",
                verificationNote = "ACTION_SET_ALARM unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened Clock to confirm alarm for %02d:%02d (%s).".format(hour, minute, label),
            verificationNote = "AlarmClock intent launched",
            category = category
        )
    }
}

class OpenDownloadsTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.DOCUMENT
    override val schema = ToolSchemaInfo(
        name = "open_downloads",
        description = "Open the system Downloads viewer.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No Downloads viewer available.",
                verificationNote = "ACTION_VIEW_DOWNLOADS unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened Downloads.",
            verificationNote = "DownloadManager viewer launched",
            category = category
        )
    }
}
