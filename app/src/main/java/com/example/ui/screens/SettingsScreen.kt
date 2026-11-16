package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.SpeechLanguageOption
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet

@Composable
fun SettingsScreen(
    viewModel: CommanderViewModel,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()
    val wakeRunning by viewModel.wakeWordServiceRunning.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val ttsVoices by viewModel.ttsVoices.collectAsStateWithLifecycle()
    val execState by viewModel.executionState.collectAsStateWithLifecycle()

    var voiceMenuExpanded by remember { mutableStateOf(false) }

    val wakeMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        viewModel.markPermissionsRequested(grants.keys.toList())
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.toggleWakeWord(true)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        viewModel.markPermissionsRequested(grants.keys.toList())
    }

    val kwsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importKwsModel(uri)
    }

    fun isPermanentlyDenied(permission: String, granted: Boolean): Boolean {
        if (granted) return false
        val wasRequested = permission in permStatus.requestedPermissions
        val canAskAgain = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
        } ?: true
        return wasRequested && !canAskAgain
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Wake Word, Voice & Permissions", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Configure offline \"Hello Buddy\" detection, voice languages, and least-privilege permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Dedicated "Hello Buddy" Wake Word Section
            item {
                GlassCard(accentBorder = if (wakeRunning) ElectricCyan else MaterialTheme.colorScheme.outline) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wake Word: \"Hello Buddy\"", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "100% offline local detection (no cloud wake APIs)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                        Switch(
                            checked = settings.wakeWordEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val needed = buildList {
                                        if (!permStatus.microphoneGranted) add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permStatus.notificationsGranted) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    if (needed.isEmpty()) {
                                        viewModel.toggleWakeWord(true)
                                    } else {
                                        wakeMicLauncher.launch(needed.toTypedArray())
                                    }
                                } else {
                                    viewModel.toggleWakeWord(false)
                                }
                            },
                            modifier = Modifier.testTag("wake_word_switch")
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = if (wakeRunning) "Foreground Listening Active" else "Service Stopped",
                            color = if (wakeRunning) EmeraldSuccess else AmberWarning,
                            icon = Icons.Default.Hearing
                        )
                        StatusPill(
                            label = if (permStatus.microphoneGranted) "Mic Granted" else "Mic Needed",
                            color = if (permStatus.microphoneGranted) EmeraldSuccess else AmberWarning,
                            icon = Icons.Default.Mic
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Sensitivity: ${(settings.wakeWordSensitivity * 100).toInt()}% (higher = easier trigger, lower = fewer false wake-ups)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.wakeWordSensitivity,
                        onValueChange = { viewModel.setWakeWordSensitivity(it) },
                        valueRange = 0.25f..0.95f,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan),
                        modifier = Modifier.testTag("wake_sensitivity_slider")
                    )

                    SettingSwitchRow(
                        title = "Activation Chime Sound",
                        subtitle = "Play a brief tone when \"Hello Buddy\" is detected",
                        checked = settings.activationSoundEnabled,
                        onCheckedChange = { viewModel.setActivationSoundEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Voice Response (TTS)",
                        subtitle = "Speak confirmations and command results aloud",
                        checked = settings.voiceResponseEnabled,
                        onCheckedChange = { viewModel.setVoiceResponseEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Continuous Wake Listening",
                        subtitle = "Automatically resume \"Hello Buddy\" listening after completing each command",
                        checked = settings.continuousListeningEnabled,
                        onCheckedChange = { viewModel.setContinuousListeningEnabled(it) }
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { kwsPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (settings.kwsModelPath.isBlank()) "Import Custom .tflite KWS Model (Optional)"
                            else "Custom .tflite KWS Loaded"
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Battery & Background Note: Wake word listening runs in an explicit foreground service with a persistent notification. Audio stays in RAM and is never recorded to disk or uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Voice & Language Section
            item {
                GlassCard {
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
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = NeuralViolet)
                            Text("Voice & Multilingual Recognition", style = MaterialTheme.typography.titleMedium)
                        }
                        StatusPill(
                            label = "Active: ${execState.activeConversationLanguage.displayName}",
                            color = ElectricCyan
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Auto mode detects English (IN/US), Telugu, Hindi, Tamil, Kannada, Malayalam & mixed-language speech/text dynamically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = "Engine: ${voiceState.backendMode.label}",
                            color = if (voiceState.isRecognitionAvailable) EmeraldSuccess else AmberWarning
                        )
                        val selectedSupport = if (settings.speechLanguage == SpeechLanguageOption.AUTO) {
                            null
                        } else {
                            voiceState.languageSupportMap[settings.speechLanguage.code]
                        }
                        if (selectedSupport != null) {
                            StatusPill(
                                label = "${settings.speechLanguage.code}: ${selectedSupport.label}",
                                color = when (selectedSupport) {
                                    com.example.domain.model.LanguageSupportStatus.VERIFIED_OFFLINE -> EmeraldSuccess
                                    com.example.domain.model.LanguageSupportStatus.VERIFIED_ONLINE_ONLY -> ElectricCyan
                                    else -> AmberWarning
                                }
                            )
                        }
                    }
                    voiceState.servicePackageName?.let { pkg ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Active Recognition Provider: $pkg",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SpeechLanguageOption.entries.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pair.forEach { lang ->
                                    FilterChip(
                                        selected = settings.speechLanguage == lang,
                                        onClick = { viewModel.setSpeechLanguage(lang) },
                                        label = { Text(lang.displayName, maxLines = 1) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("lang_chip_${lang.name}")
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    voiceState.missingPackWarning?.let { warn ->
                        Spacer(Modifier.height(6.dp))
                        Text(warn, style = MaterialTheme.typography.bodySmall, color = AmberWarning)
                    }
                    voiceState.modelDownloadStatus?.let { dlMsg ->
                        Spacer(Modifier.height(4.dp))
                        Text(dlMsg, style = MaterialTheme.typography.bodySmall, color = ElectricCyan)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.triggerSpeechModelDownload() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Download Voice Pack", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { viewModel.openOfflineSpeechSettings() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Voice Settings", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.refreshSpeechCapabilities() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Re-check Speech Service Support")
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Speech Speed: ${"%.1f".format(settings.speechSpeed)}x", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = settings.speechSpeed,
                        onValueChange = { viewModel.setSpeechSpeed(it) },
                        valueRange = 0.6f..1.8f,
                        colors = SliderDefaults.colors(thumbColor = NeuralViolet, activeTrackColor = NeuralViolet)
                    )

                    if (ttsVoices.isNotEmpty()) {
                        Box {
                            OutlinedButton(onClick = { voiceMenuExpanded = true }) {
                                Text(
                                    text = if (settings.selectedTtsVoice.isBlank()) "TTS Voice: System Default"
                                    else "Voice: ${settings.selectedTtsVoice.take(22)}"
                                )
                            }
                            DropdownMenu(
                                expanded = voiceMenuExpanded,
                                onDismissRequest = { voiceMenuExpanded = false }
                            ) {
                                ttsVoices.take(12).forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text("${voice.locale.displayName} (${voice.name.take(18)})") },
                                        onClick = {
                                            viewModel.setSelectedTtsVoice(voice.name)
                                            voiceMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Permission Center (Reactive StateFlow + Permanent Denial Recovery + Exact Alarm)
            item {
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = ElectricCyan)
                            Text("Permission & Security Center", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = { viewModel.refreshPermissions() },
                            modifier = Modifier.testTag("refresh_permissions_button")
                        ) {
                            Text("Refresh")
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    val micPermDenied = isPermanentlyDenied(Manifest.permission.RECORD_AUDIO, permStatus.microphoneGranted)
                    PermissionRow(
                        title = "Microphone",
                        granted = permStatus.microphoneGranted,
                        permanentlyDenied = micPermDenied
                    ) {
                        if (micPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val notifPermDenied = isPermanentlyDenied(Manifest.permission.POST_NOTIFICATIONS, permStatus.notificationsGranted)
                        PermissionRow(
                            title = "Notifications",
                            granted = permStatus.notificationsGranted,
                            permanentlyDenied = notifPermDenied
                        ) {
                            if (notifPermDenied) viewModel.openAppSettings()
                            else permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PermissionRow(
                            title = "Exact Reminder Alarms",
                            granted = permStatus.exactAlarmGranted,
                            permanentlyDenied = false,
                            actionLabel = if (permStatus.exactAlarmGranted) "Review" else "Allow"
                        ) {
                            viewModel.openExactAlarmSettings()
                        }
                    }

                    PermissionRow("Accessibility Automation", permStatus.accessibilityEnabled) {
                        viewModel.openAccessibilitySettings()
                    }
                    PermissionRow("Modify Brightness (Write Settings)", permStatus.writeSettingsGranted) {
                        viewModel.openWriteSettings()
                    }

                    val contactsPermDenied = isPermanentlyDenied(Manifest.permission.READ_CONTACTS, permStatus.contactsGranted)
                    PermissionRow(
                        title = "Contacts (Drafting & Lookup)",
                        granted = permStatus.contactsGranted,
                        permanentlyDenied = contactsPermDenied
                    ) {
                        if (contactsPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    }

                    val calendarPermDenied = isPermanentlyDenied(Manifest.permission.WRITE_CALENDAR, permStatus.calendarGranted)
                    PermissionRow(
                        title = "Calendar Events",
                        granted = permStatus.calendarGranted,
                        permanentlyDenied = calendarPermDenied
                    ) {
                        if (calendarPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }

                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onRequestScreenCapture, modifier = Modifier.fillMaxWidth()) {
                        Text("Trigger Local Screen Capture Consent")
                    }
                }
            }

            // Privacy & Zero-API-Key Policy Pledge
            item {
                GlassCard(accentBorder = EmeraldSuccess.copy(alpha = 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldSuccess)
                        Text("Privacy & Zero-API-Key Architecture", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "• No Gemini, OpenAI, OpenRouter, or paid LLM API keys.\n" +
                            "• No screenshots, voice audio, or command history ever leave your device.\n" +
                            "• Messaging, profile updates, and file actions require explicit confirmation.\n" +
                            "• Never executes arbitrary AI-generated code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    permanentlyDenied: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = when {
                    granted -> "Granted"
                    permanentlyDenied -> "Denied (Enable in App Settings)"
                    else -> "Not granted"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) EmeraldSuccess else AmberWarning
            )
        }
        OutlinedButton(onClick = onAction) {
            Text(
                actionLabel ?: when {
                    granted -> "Review"
                    permanentlyDenied -> "Open App Settings"
                    else -> "Grant"
                }
            )
        }
    }
}
