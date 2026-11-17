package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.memory.MemoryCategory
import com.example.notifications.NotificationDigestItem
import com.example.notifications.NotificationPriorityLevel
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.NeuralViolet
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantHubScreen(
    viewModel: CommanderViewModel,
    initialTab: Int = 0,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()
    val memories by viewModel.personalMemories.collectAsStateWithLifecycle()
    val routines by viewModel.automationRoutines.collectAsStateWithLifecycle()
    val routineLogs by viewModel.routineLogs.collectAsStateWithLifecycle()
    val notifications by viewModel.notificationsDigest.collectAsStateWithLifecycle()
    val listenerConnected by viewModel.notificationListenerConnected.collectAsStateWithLifecycle()

    // Dialog states
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var showClearMemoryConfirm by remember { mutableStateOf(false) }
    var memoryKeyInput by remember { mutableStateOf("") }
    var memoryValueInput by remember { mutableStateOf("") }
    var memoryCategory by remember { mutableStateOf(MemoryCategory.PREFERENCE) }

    var showAddRoutineDialog by remember { mutableStateOf(false) }
    var routineNameInput by remember { mutableStateOf("") }
    var routineTriggerInput by remember { mutableStateOf("") }
    var selectedRoutineTool by remember { mutableStateOf("get_battery_status") }
    var routineScheduleHour by remember { mutableStateOf("") }
    var routineScheduleMinute by remember { mutableStateOf("") }

    var replyTarget by remember { mutableStateOf<NotificationDigestItem?>(null) }
    var replyTextInput by remember { mutableStateOf("") }

    if (showAddMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            containerColor = MidnightNavy,
            title = { Text("Save to Encrypted AI Memory", color = ElectricCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Encrypted on-device. Sensitive data such as passwords, OTPs, and card numbers are automatically blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = memoryKeyInput,
                        onValueChange = { memoryKeyInput = it },
                        label = { Text("Topic / Label (e.g. Work Hours)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("memory_key_input")
                    )
                    OutlinedTextField(
                        value = memoryValueInput,
                        onValueChange = { memoryValueInput = it },
                        label = { Text("Preference or Note") },
                        modifier = Modifier.fillMaxWidth().testTag("memory_value_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(MemoryCategory.PREFERENCE, MemoryCategory.WORKFLOW, MemoryCategory.LANGUAGE).forEach { cat ->
                            FilterChip(
                                selected = memoryCategory == cat,
                                onClick = { memoryCategory = cat },
                                label = { Text(cat.label, maxLines = 1) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.savePersonalMemory(
                            key = memoryKeyInput.ifBlank { memoryValueInput.take(24) },
                            value = memoryValueInput,
                            category = memoryCategory
                        )
                        memoryKeyInput = ""
                        memoryValueInput = ""
                        showAddMemoryDialog = false
                    },
                    enabled = memoryValueInput.isNotBlank(),
                    modifier = Modifier.testTag("confirm_save_memory_button")
                ) {
                    Text("Confirm Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showClearMemoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearMemoryConfirm = false },
            containerColor = MidnightNavy,
            title = { Text("Clear All AI Memories?", color = CrimsonAlert) },
            text = { Text("All locally saved personal AI memory records will be permanently deleted.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearAllMemories()
                    showClearMemoryConfirm = false
                }) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddRoutineDialog) {
        val availableTools = listOf(
            "get_battery_status" to "Battery Check",
            "get_network_status" to "Network Check",
            "summarize_notifications" to "Alert Summary",
            "toggle_flashlight" to "Flashlight",
            "adjust_volume" to "Mute Volume"
        )
        AlertDialog(
            onDismissRequest = { showAddRoutineDialog = false },
            containerColor = MidnightNavy,
            title = { Text("Create Smart Routine", color = ElectricCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = routineNameInput,
                        onValueChange = { routineNameInput = it },
                        label = { Text("Routine Name (e.g. Evening Check)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("routine_name_input")
                    )
                    OutlinedTextField(
                        value = routineTriggerInput,
                        onValueChange = { routineTriggerInput = it },
                        label = { Text("Voice Trigger (e.g. evening check)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("routine_trigger_input")
                    )
                    Text("Action Step", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        availableTools.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { (toolId, label) ->
                                    FilterChip(
                                        selected = selectedRoutineTool == toolId,
                                        onClick = { selectedRoutineTool = toolId },
                                        label = { Text(label, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = routineScheduleHour,
                            onValueChange = { s -> routineScheduleHour = s.filter { it.isDigit() }.take(2) },
                            label = { Text("Hour 0-23 (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = routineScheduleMinute,
                            onValueChange = { s -> routineScheduleMinute = s.filter { it.isDigit() }.take(2) },
                            label = { Text("Min 0-59 (opt)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val h = routineScheduleHour.toIntOrNull()?.takeIf { it in 0..23 }
                        val m = routineScheduleMinute.toIntOrNull()?.takeIf { it in 0..59 }
                        val (argK, argV) = when (selectedRoutineTool) {
                            "toggle_flashlight" -> "state" to "toggle"
                            "adjust_volume" -> "direction" to "mute"
                            else -> "" to ""
                        }
                        viewModel.createCustomRoutine(
                            name = routineNameInput,
                            voiceTrigger = routineTriggerInput.ifBlank { routineNameInput.lowercase() },
                            toolName = selectedRoutineTool,
                            toolArgKey = argK,
                            toolArgVal = argV,
                            scheduleHour = h,
                            scheduleMinute = if (h != null) (m ?: 0) else null
                        )
                        routineNameInput = ""
                        routineTriggerInput = ""
                        routineScheduleHour = ""
                        routineScheduleMinute = ""
                        showAddRoutineDialog = false
                    },
                    enabled = routineNameInput.isNotBlank(),
                    modifier = Modifier.testTag("confirm_save_routine_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRoutineDialog = false }) { Text("Cancel") }
            }
        )
    }

    replyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { replyTarget = null },
            containerColor = MidnightNavy,
            title = { Text("Confirm Quick Reply", color = ElectricCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Send quick reply to ${target.appName} (${target.title}):",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = replyTextInput,
                        onValueChange = { replyTextInput = it },
                        label = { Text("Reply message") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.sendNotificationQuickReply(target.key, replyTextInput)
                        replyTextInput = ""
                        replyTarget = null
                    },
                    enabled = replyTextInput.isNotBlank()
                ) {
                    Text("Confirm & Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { replyTarget = null }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            TopAppBar(
                title = { Text("Smart Hub") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("hub_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Dashboard")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = ElectricCyan
                )
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                val tabs = listOf(
                    Triple(0, "Memory", "hub_tab_memory"),
                    Triple(1, "Routines", "hub_tab_routines"),
                    Triple(2, "Alerts", "hub_tab_notifications")
                )
                tabs.forEach { (idx, label, tag) ->
                    SegmentedButton(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                        modifier = Modifier.testTag(tag)
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("hub_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        item {
                            GlassCard(accentBorder = ElectricCyan.copy(alpha = 0.6f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Shield, contentDescription = null, tint = EmeraldSuccess)
                                        Text("Encrypted Personal AI Memory", style = MaterialTheme.typography.titleMedium)
                                    }
                                    StatusPill(label = "${memories.size} saved", color = ElectricCyan)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Preferences & facts are encrypted with AndroidKeyStore AES-256-GCM on device. OTPs, passwords, PINs, and card numbers are automatically rejected.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Use Memory in Local AI", style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = settings.memoryInjectionEnabled,
                                        onCheckedChange = { viewModel.setMemoryInjectionEnabled(it) }
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { showAddMemoryDialog = true },
                                        modifier = Modifier.weight(1f).testTag("add_memory_button")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add Memory")
                                    }
                                    if (memories.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { showClearMemoryConfirm = true },
                                            modifier = Modifier.testTag("clear_memories_button")
                                        ) {
                                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Clear All")
                                        }
                                    }
                                }
                            }
                        }

                        if (memories.isEmpty()) {
                            item {
                                GlassCard {
                                    Text(
                                        text = "No personal memories stored yet. Say \"Remember that my preferred language is Telugu\" or tap Add Memory above.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(memories, key = { "mem_${it.id}" }) { memo ->
                                GlassCard(contentPadding = PaddingValues(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                                                Text(memo.summaryLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                StatusPill(
                                                    label = if (memo.isHardwareEncrypted) "AES-256" else memo.category.label,
                                                    color = EmeraldSuccess
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(memo.valueText, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        IconButton(onClick = { viewModel.deletePersonalMemory(memo.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete memory", tint = CrimsonAlert)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        item {
                            GlassCard(accentBorder = NeuralViolet.copy(alpha = 0.7f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = NeuralViolet)
                                        Text("Smart Automation Routines", style = MaterialTheme.typography.titleMedium)
                                    }
                                    StatusPill(label = "${routines.size} configured", color = NeuralViolet)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Run multi-step workflows by voice, daily schedule, or one-tap.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { showAddRoutineDialog = true },
                                    modifier = Modifier.fillMaxWidth().testTag("add_routine_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Create Custom Routine")
                                }
                            }
                        }

                        items(routines, key = { "rt_${it.id}" }) { routine ->
                            GlassCard(
                                contentPadding = PaddingValues(12.dp),
                                accentBorder = if (routine.isEnabled) ElectricCyan.copy(alpha = 0.5f) else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(routine.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            StatusPill(
                                                label = if (routine.isPreset) "PRESET" else "CUSTOM",
                                                color = if (routine.isPreset) NeuralViolet else ElectricCyan
                                            )
                                        }
                                        Text(
                                            text = routine.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Voice: \"${routine.voiceTriggers}\"" +
                                                if (routine.scheduleHour != null) " • Daily %02d:%02d".format(routine.scheduleHour, routine.scheduleMinute ?: 0) else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ElectricCyan
                                        )
                                    }
                                    Switch(
                                        checked = routine.isEnabled,
                                        onCheckedChange = { viewModel.toggleRoutineEnabled(routine.id, it) }
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.runRoutineNow(routine.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Run Now")
                                    }
                                    if (!routine.isPreset) {
                                        IconButton(onClick = { viewModel.deleteRoutine(routine.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete routine", tint = CrimsonAlert)
                                        }
                                    }
                                }
                            }
                        }

                        if (routineLogs.isNotEmpty()) {
                            item {
                                Text(
                                    text = "RECENT ROUTINE LOGS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(routineLogs.take(10), key = { "rlog_${it.id}" }) { log ->
                                GlassCard(contentPadding = PaddingValues(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${log.routineName} (${log.triggerSource})", style = MaterialTheme.typography.labelLarge)
                                        StatusPill(
                                            label = log.status,
                                            color = if (log.status == "SUCCESS") EmeraldSuccess else AmberWarning
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(log.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(log.executedAt)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        item {
                            GlassCard(
                                accentBorder = if (permStatus.notificationListenerGranted) EmeraldSuccess.copy(alpha = 0.6f)
                                else AmberWarning.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = ElectricCyan)
                                        Text("Notification Intelligence", style = MaterialTheme.typography.titleMedium)
                                    }
                                    StatusPill(
                                        label = when {
                                            listenerConnected -> "ACTIVE"
                                            permStatus.notificationListenerGranted -> "ENABLED"
                                            else -> "ACCESS OFF"
                                        },
                                        color = if (permStatus.notificationListenerGranted) EmeraldSuccess else AmberWarning
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Summarizes non-sensitive alerts locally. OTPs and banking alerts are automatically skipped.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Enable Local Digest", style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = settings.notificationIntelligenceEnabled,
                                        onCheckedChange = { viewModel.setNotificationIntelligenceEnabled(it) }
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.openNotificationAccessSettings() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (permStatus.notificationListenerGranted) "Review Access" else "Enable Access")
                                    }
                                    if (notifications.isNotEmpty()) {
                                        OutlinedButton(onClick = { viewModel.clearCapturedNotifications() }) {
                                            Text("Clear")
                                        }
                                    }
                                }
                                if (!permStatus.notificationListenerGranted) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Android 13+ Tip: If Notification Access appears greyed out for sideloaded APKs, open App Info -> 3-dot menu -> Allow restricted settings.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberWarning
                                    )
                                }
                            }
                        }

                        if (notifications.isEmpty()) {
                            item {
                                GlassCard {
                                    Text(
                                        text = "No non-sensitive notifications captured in this session yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(notifications, key = { it.key }) { item ->
                                GlassCard(contentPadding = PaddingValues(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(item.appName, style = MaterialTheme.typography.labelLarge, color = ElectricCyan)
                                        StatusPill(
                                            label = item.priority.label,
                                            color = when (item.priority) {
                                                NotificationPriorityLevel.HIGH -> CrimsonAlert
                                                NotificationPriorityLevel.NORMAL -> ElectricCyan
                                                NotificationPriorityLevel.LOW -> NeuralViolet
                                            }
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (item.body.isNotBlank()) {
                                        Text(item.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (item.canQuickReply) {
                                            OutlinedButton(
                                                onClick = {
                                                    replyTextInput = ""
                                                    replyTarget = item
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Quick Reply")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.toggleNotificationPackageBlocked(item.packageName, true) }
                                        ) {
                                            Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Mute App")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
