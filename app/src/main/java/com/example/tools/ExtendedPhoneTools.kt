package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.example.ai.DeviceCompatibilityManager
import com.example.automation.AutomationEngine
import com.example.domain.model.ActionCategory
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.ToolResult
import com.example.domain.model.ToolSchemaInfo
import com.example.memory.MemoryCategory
import com.example.memory.MemorySecurityHelper
import com.example.memory.PersonalMemoryRepository
import com.example.network.ConnectivityMonitor
import com.example.notifications.CommanderNotificationListenerService
import com.example.notifications.NotificationIntelligenceFilter

class ToggleFlashlightTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SETTINGS
    override val schema = ToolSchemaInfo(
        name = "toggle_flashlight",
        description = "Turn device camera flashlight torch on, off, or toggle it.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("state" to "on | off | toggle"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Camera service unavailable on this device.",
                verificationNote = "CameraManager null",
                category = category
            )

        val torchCameraId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.UNSUPPORTED,
            message = "No hardware flashlight unit detected on this device.",
            verificationNote = "FLASH_INFO_AVAILABLE false across all cameras",
            category = category
        )

        ensureTorchCallbackRegistered(cm)

        val requested = arguments["state"]?.lowercase() ?: "toggle"
        val enable = when (requested) {
            "on", "true", "enable" -> true
            "off", "false", "disable" -> false
            else -> !lastTorchEnabled
        }

        return runCatching {
            cm.setTorchMode(torchCameraId, enable)
            lastTorchEnabled = enable
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = if (enable) "Flashlight turned ON." else "Flashlight turned OFF.",
                verificationNote = "CameraManager.setTorchMode($torchCameraId, $enable)",
                data = mapOf("torch_on" to enable.toString()),
                category = category
            )
        }.getOrElse { err ->
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "Unable to toggle flashlight: ${err.message ?: "hardware busy"}",
                verificationNote = "setTorchMode failed",
                category = category
            )
        }
    }

    companion object {
        @Volatile
        private var lastTorchEnabled = false

        @Volatile
        private var callbackRegistered = false

        private fun ensureTorchCallbackRegistered(cm: CameraManager) {
            if (callbackRegistered) return
            synchronized(this) {
                if (callbackRegistered) return
                runCatching {
                    cm.registerTorchCallback(
                        object : CameraManager.TorchCallback() {
                            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                                lastTorchEnabled = enabled
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                    callbackRegistered = true
                }
            }
        }
    }
}

class SetTimerTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.CALENDAR
    override val schema = ToolSchemaInfo(
        name = "set_timer",
        description = "Start a countdown timer in the system Clock app.",
        requiredPermissions = listOf("com.android.alarm.permission.SET_ALARM"),
        requiresConfirmation = false,
        parameters = mapOf("seconds" to "integer seconds", "label" to "optional label"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val seconds = (arguments["seconds"]?.toIntOrNull()
            ?: (arguments["minutes"]?.toIntOrNull()?.times(60))
            ?: 300).coerceIn(5, 86_400)
        val label = arguments["label"]?.trim().orEmpty().ifBlank { "AI Commander Timer" }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No Clock app available to set a timer.",
                verificationNote = "ACTION_SET_TIMER unresolved",
                category = category
            )
        }
        context.startActivity(intent)
        val readable = if (seconds >= 60 && seconds % 60 == 0) {
            "${seconds / 60} minute(s)"
        } else {
            "$seconds second(s)"
        }
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened Clock to confirm $readable timer ($label).",
            verificationNote = "AlarmClock ACTION_SET_TIMER started (${seconds}s)",
            data = mapOf("seconds" to seconds.toString(), "label" to label),
            category = category
        )
    }
}

class GetBatteryStatusTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SYSTEM
    override val schema = ToolSchemaInfo(
        name = "get_battery_status",
        description = "Read current battery percentage, charging status, and temperature.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val pct = if (level >= 0) {
            (level * 100) / scale
        } else {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1
        }
        val statusInt = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempStr = if (tempTenths > 0) " • ${tempTenths / 10.0}°C" else ""

        val pctLabel = if (pct >= 0) "$pct%" else "unknown"
        val chargeLabel = if (isCharging) "Charging" else "Discharging"
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Battery is at $pctLabel ($chargeLabel$tempStr).",
            verificationNote = "BatteryManager telemetry read",
            data = mapOf("battery_pct" to pct.toString(), "charging" to isCharging.toString()),
            category = category
        )
    }
}

class GetDeviceInfoTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.SYSTEM
    override val schema = ToolSchemaInfo(
        name = "get_device_info",
        description = "Inspect device model, Android version, ABI, RAM, and available local storage.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val compat = DeviceCompatibilityManager(context).inspectDevice()
        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val summary = "$deviceName • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • " +
            "ABI ${compat.cpuAbi} • RAM ${compat.availableRamMb}/${compat.totalRamMb} MB free • " +
            "Storage ${compat.availableStorageMb} MB free."
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = summary,
            verificationNote = "Device telemetry collected locally",
            data = mapOf(
                "abi" to compat.cpuAbi,
                "total_ram_mb" to compat.totalRamMb.toString(),
                "free_storage_mb" to compat.availableStorageMb.toString()
            ),
            category = category
        )
    }
}

class GetNetworkStatusTool(
    private val context: Context,
    private val connectivityMonitor: ConnectivityMonitor? = null
) : CommanderTool {
    override val category = ActionCategory.SYSTEM
    override val schema = ToolSchemaInfo(
        name = "get_network_status",
        description = "Check current internet connectivity status and active network transport.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val snap = connectivityMonitor?.refresh() ?: ConnectivityMonitor(context).let {
            val s = it.refresh()
            it.shutdown()
            s
        }
        val msg = if (snap.isOnline) {
            "Device is Online via ${snap.transportLabel}${if (snap.isMetered) " (Metered)" else ""}."
        } else {
            "Device is currently Offline. Local commands, offline AI models, notes, reminders, and OCR continue working."
        }
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = msg,
            verificationNote = "ConnectivityManager status=${snap.transportLabel}",
            data = mapOf("online" to snap.isOnline.toString(), "transport" to snap.transportLabel),
            category = category
        )
    }
}

class DialPhoneNumberTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.MESSAGING
    override val schema = ToolSchemaInfo(
        name = "dial_phone_number",
        description = "Open the phone dialer with a resolved contact or phone number (requires confirmation; user taps Call).",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("recipient" to "contact name or phone number"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? {
        val target = arguments["recipient"]?.ifBlank { arguments["phone_number"] }
        return if (target.isNullOrBlank()) "Specify a phone number or contact name to dial." else null
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val raw = (arguments["recipient"]?.ifBlank { arguments["phone_number"] } ?: "").trim()
        val lookup = resolveContactPhone(context, raw)
        val resolvedNumber = when (lookup) {
            is ContactLookupResult.Matched -> lookup.dialString
            is ContactLookupResult.Ambiguous -> {
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.FAILED,
                    message = "Multiple contacts match \"$raw\" (${lookup.names.joinToString(", ")}). Say the full contact name or number.",
                    verificationNote = "Ambiguous dial contact match",
                    category = category
                )
            }
            ContactLookupResult.PermissionMissing -> {
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.PERMISSION_REQUIRED,
                    message = "Could not resolve contact \"$raw\" to a phone number. Provide digits directly or grant Contacts permission.",
                    verificationNote = "READ_CONTACTS not granted",
                    missingPermissions = listOf(Manifest.permission.READ_CONTACTS),
                    category = category
                )
            }
            ContactLookupResult.NotFound -> {
                return ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.FAILED,
                    message = "No phone number found for \"$raw\".",
                    verificationNote = "Recipient dial digits unresolved",
                    category = category
                )
            }
        }

        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$resolvedNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (dialIntent.resolveActivity(context.packageManager) == null) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No Phone dialer app available on this device.",
                verificationNote = "ACTION_DIAL unresolved",
                category = category
            )
        }
        context.startActivity(dialIntent)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = "Opened Phone dialer with $resolvedNumber (tap Call to place the call).",
            verificationNote = "ACTION_DIAL launched safely",
            data = mapOf("number" to resolvedNumber),
            category = category
        )
    }
}

class SaveMemoryTool(private val memoryRepository: PersonalMemoryRepository) : CommanderTool {
    override val category = ActionCategory.MEMORY
    override val schema = ToolSchemaInfo(
        name = "save_memory",
        description = "Save a non-sensitive personal preference or fact in encrypted on-device AI Memory (requires user confirmation).",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("key" to "topic key", "value" to "fact or preference", "category" to "PREFERENCE | WORKFLOW | LANGUAGE | GENERAL"),
        category = category.name
    )

    override fun validate(arguments: Map<String, String>): String? {
        val value = arguments["value"].orEmpty()
        if (value.isBlank()) return "Provide what you want me to remember."
        if (MemorySecurityHelper.containsForbiddenSecret(value) ||
            MemorySecurityHelper.containsForbiddenSecret(arguments["key"].orEmpty())
        ) {
            return "Blocked: OTPs, passwords, PINs, card numbers, and banking details are never saved to AI Memory."
        }
        return null
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val key = arguments["key"]?.ifBlank { arguments["value"]?.take(28) } ?: "preference"
        val value = arguments.getValue("value")
        val cat = runCatching {
            MemoryCategory.valueOf((arguments["category"] ?: "GENERAL").uppercase())
        }.getOrDefault(MemoryCategory.GENERAL)

        val saved = memoryRepository.saveMemory(
            rawKey = key,
            value = value,
            category = cat,
            summaryLabel = key
        )
        return saved.fold(
            onSuccess = { item ->
                ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.SUCCESS,
                    message = "Saved to encrypted personal memory (${item.summaryLabel}).",
                    verificationNote = "Stored in Room personal_memories (hardwareKeystore=${item.isHardwareEncrypted})",
                    data = mapOf("memory_key" to item.memoryKey),
                    category = category
                )
            },
            onFailure = { err ->
                ToolResult(
                    toolName = schema.name,
                    status = ExecutionStatus.FAILED,
                    message = err.message ?: "Unable to save memory.",
                    verificationNote = "Memory save rejected",
                    category = category
                )
            }
        )
    }
}

class RecallMemoryTool(private val memoryRepository: PersonalMemoryRepository) : CommanderTool {
    override val category = ActionCategory.MEMORY
    override val schema = ToolSchemaInfo(
        name = "recall_memory",
        description = "Recall personal preferences or facts stored in on-device encrypted AI Memory.",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("query" to "optional topic filter"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["query"].orEmpty().trim()
        val items = if (query.isBlank()) {
            memoryRepository.getAllDecrypted()
        } else {
            memoryRepository.recallRelevant(query, limit = 6)
        }
        val msg = if (items.isEmpty()) {
            "Your personal AI memory is currently empty."
        } else {
            "Remembered ${items.size} item(s): " +
                items.take(5).joinToString("; ") { "${it.summaryLabel}: ${it.valueText}" }
        }
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = msg,
            verificationNote = "Decrypted ${items.size} memory record(s) locally",
            data = mapOf("count" to items.size.toString()),
            category = category
        )
    }
}

class ForgetMemoryTool(private val memoryRepository: PersonalMemoryRepository) : CommanderTool {
    override val category = ActionCategory.MEMORY
    override val schema = ToolSchemaInfo(
        name = "forget_memory",
        description = "Delete matching personal AI Memory records from device storage.",
        requiredPermissions = emptyList(),
        requiresConfirmation = true,
        parameters = mapOf("query" to "topic or keyword to forget (or 'all')"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["query"].orEmpty().trim()
        return if (query.equals("all", ignoreCase = true) || query.isBlank()) {
            memoryRepository.clearAll()
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = "Cleared all personal AI memories from device.",
                verificationNote = "personal_memories table cleared",
                category = category
            )
        } else {
            val count = memoryRepository.deleteByQuery(query)
            ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.SUCCESS,
                message = if (count > 0) "Deleted $count memory item(s) matching \"$query\"." else "No memory items matched \"$query\".",
                verificationNote = "Removed $count row(s)",
                category = category
            )
        }
    }
}

class SummarizeNotificationsTool(private val context: Context) : CommanderTool {
    override val category = ActionCategory.NOTIFICATIONS
    override val schema = ToolSchemaInfo(
        name = "summarize_notifications",
        description = "Summarize recent non-sensitive notifications captured locally by Notification Intelligence.",
        requiredPermissions = listOf("Notification Access (BIND_NOTIFICATION_LISTENER_SERVICE)"),
        requiresConfirmation = false,
        parameters = emptyMap(),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        if (!CommanderNotificationListenerService.isPermissionGranted(context)) {
            return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.PERMISSION_REQUIRED,
                message = "Enable AI Commander Notification Access in Android Settings to read & summarize notifications.",
                verificationNote = "NotificationListenerService access disabled",
                category = category
            )
        }
        val items = CommanderNotificationListenerService.notifications.value
        val summary = NotificationIntelligenceFilter.summarizeLocally(items)
        return ToolResult(
            toolName = schema.name,
            status = ExecutionStatus.SUCCESS,
            message = summary,
            verificationNote = "Summarized ${items.size} redacted notification(s) in-memory",
            data = mapOf("count" to items.size.toString()),
            category = category
        )
    }
}

class RunRoutineTool(private val automationEngineProvider: () -> AutomationEngine) : CommanderTool {
    constructor(automationEngine: AutomationEngine) : this({ automationEngine })

    override val category = ActionCategory.AUTOMATION
    override val schema = ToolSchemaInfo(
        name = "run_routine",
        description = "Execute a configured Smart Automation routine (e.g., Morning Briefing, Night Wind-Down, Focus Mode).",
        requiredPermissions = emptyList(),
        requiresConfirmation = false,
        parameters = mapOf("routine_name" to "name or voice trigger of routine"),
        category = category.name
    )

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val automationEngine = automationEngineProvider()
        val query = arguments["routine_name"].orEmpty().trim()
        val match = automationEngine.findRoutineByNameOrTrigger(query)
            ?: return ToolResult(
                toolName = schema.name,
                status = ExecutionStatus.FAILED,
                message = "No routine found matching \"$query\".",
                verificationNote = "Routine lookup failed",
                category = category
            )
        val log = automationEngine.executeRoutine(match.id, triggerSource = "VOICE")
        val status = when (log.status) {
            "SUCCESS", "PARTIAL" -> ExecutionStatus.SUCCESS
            else -> ExecutionStatus.FAILED
        }
        return ToolResult(
            toolName = schema.name,
            status = status,
            message = "Routine \"${match.name}\" (${log.status}): ${log.summary}",
            verificationNote = "Routine #${match.id} executed & logged",
            data = mapOf("routine_id" to match.id.toString(), "status" to log.status),
            category = category
        )
    }
}
