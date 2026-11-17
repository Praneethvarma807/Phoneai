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
import com.example.ui.components.sanitizeUiDiagnostics
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
    val enrollmentBusy by viewModel.primarySpeakerEnrollmentBusy.collectAsStateWithLifecycle()
    val speakerNote by viewModel.lastSpeakerVerificationNote.collectAsStateWithLifecycle()
    val audioPipelineStatus by viewModel.audioPipelineStatus.collectAsStateWithLifecycle()

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

    val enrollMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enrollPrimarySpeaker()
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
                Text("Voice & Permissions", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "Manage wake word, languages, smart privacy toggles, and app permissions.",
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
                                text = "Offline background detection",
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
                        text = "Sensitivity: ${(settings.wakeWordSensitivity * 100).toInt()}%",
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
                        title = "Activation Chime",
                        subtitle = "Play a tone when \"Hello Buddy\" is heard",
                        checked = settings.activationSoundEnabled,
                        onCheckedChange = { viewModel.setActivationSoundEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Voice Responses",
                        subtitle = "Speak replies aloud",
                        checked = settings.voiceResponseEnabled,
                        onCheckedChange = { viewModel.setVoiceResponseEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Follow-Up Listening",
                        subtitle = "Listen for follow-up commands automatically",
                        checked = settings.continuousListeningEnabled,
                        onCheckedChange = { viewModel.setContinuousListeningEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Personal AI Memory",
                        subtitle = "Remember saved preferences during planning",
                        checked = settings.memoryInjectionEnabled,
                        onCheckedChange = { viewModel.setMemoryInjectionEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Notification Digest",
                        subtitle = "Summarize non-sensitive alerts locally",
                        checked = settings.notificationIntelligenceEnabled,
                        onCheckedChange = { viewModel.setNotificationIntelligenceEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Offline Strict Mode",
                        subtitle = "Keep all actions strictly on-device",
                        checked = settings.offlineStrictMode,
                        onCheckedChange = { viewModel.setOfflineStrictMode(it) }
                    )

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { kwsPickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (settings.kwsModelPath.isBlank()) "Import Custom Wake Word Model"
                            else "Custom Wake Word Model Loaded"
                        )
                    }
                }
            }

            // Primary Speaker Intelligence Card
            item {
                GlassCard(
                    accentBorder = if (settings.primarySpeakerOnlyEnabled) EmeraldSuccess.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Primary Speaker Intelligence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Reject TV, music & other voices with DSP noise suppression and Dual-VAD",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.primarySpeakerOnlyEnabled,
                            enabled = settings.hasPrimarySpeakerProfile,
                            onCheckedChange = { viewModel.setPrimarySpeakerOnlyEnabled(it) },
                            modifier = Modifier.testTag("primary_speaker_only_switch")
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = if (settings.hasPrimarySpeakerProfile) "Voice Enrolled (${settings.primarySpeakerSamplesCount}x)" else "Not Enrolled",
                            color = if (settings.hasPrimarySpeakerProfile) EmeraldSuccess else AmberWarning,
                            icon = Icons.Default.RecordVoiceOver
                        )
                        StatusPill(
                            label = audioPipelineStatus,
                            color = ElectricCyan
                        )
                    }

                    if (!speakerNote.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = speakerNote!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Speaker Match Strictness: ${(settings.speakerVerificationThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.speakerVerificationThreshold,
                        onValueChange = { viewModel.setSpeakerVerificationThreshold(it) },
                        valueRange = 0.55f..0.90f,
                        colors = SliderDefaults.colors(thumbColor = EmeraldSuccess, activeTrackColor = EmeraldSuccess),
                        modifier = Modifier.testTag("speaker_threshold_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (permStatus.microphoneGranted) {
                                    viewModel.enrollPrimarySpeaker()
                                } else {
                                    enrollMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !enrollmentBusy,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("enroll_primary_speaker_button")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    enrollmentBusy -> "Listening (2s)…"
                                    settings.hasPrimarySpeakerProfile -> "Re-Enroll Voice"
                                    else -> "Enroll My Voice"
                                }
                            )
                        }
                        if (settings.hasPrimarySpeakerProfile) {
                            OutlinedButton(
                                onClick = { viewModel.clearPrimarySpeakerProfile() },
                                modifier = Modifier.testTag("clear_speaker_profile_button")
                            ) {
                                Text("Clear")
                            }
                        }
                    }
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
                        text = "Auto supports English, Telugu, Hindi, Tamil, Kannada, Malayalam, and mixed speech.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = if (settings.developerModeEnabled) {
                                voiceState.backendMode.label
                            } else if (voiceState.isRecognitionAvailable) {
                                "Voice Ready"
                            } else {
                                "Voice Setup Needed"
                            },
                            color = if (voiceState.isRecognitionAvailable) EmeraldSuccess else AmberWarning
                        )
                        val selectedSupport = if (settings.speechLanguage == SpeechLanguageOption.AUTO) {
                            null
                        } else {
                            voiceState.languageSupportMap[settings.speechLanguage.code]
                        }
                        if (settings.developerModeEnabled && selectedSupport != null) {
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
                        Text(
                            text = sanitizeUiDiagnostics(warn, settings.developerModeEnabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                    voiceState.modelDownloadStatus?.let { dlMsg ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = sanitizeUiDiagnostics(dlMsg, settings.developerModeEnabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
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
                        Text("Refresh Voice Support")
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
                            Text("Permissions", style = MaterialTheme.typography.titleMedium)
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
                            title = "Exact Alarms",
                            granted = permStatus.exactAlarmGranted,
                            permanentlyDenied = false,
                            actionLabel = if (permStatus.exactAlarmGranted) "Review" else "Allow"
                        ) {
                            viewModel.openExactAlarmSettings()
                        }
                    }

                    val cameraPermDenied = isPermanentlyDenied(Manifest.permission.CAMERA, permStatus.cameraGranted)
                    PermissionRow(
                        title = "Camera",
                        granted = permStatus.cameraGranted,
                        permanentlyDenied = cameraPermDenied
                    ) {
                        if (cameraPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    }

                    PermissionRow(
                        title = "Notification Access",
                        granted = permStatus.notificationListenerGranted,
                        permanentlyDenied = false,
                        actionLabel = if (permStatus.notificationListenerGranted) "Review" else "Allow"
                    ) {
                        viewModel.openNotificationAccessSettings()
                    }

                    PermissionRow("Accessibility", permStatus.accessibilityEnabled) {
                        viewModel.openAccessibilitySettings()
                    }
                    PermissionRow("System Settings", permStatus.writeSettingsGranted) {
                        viewModel.openWriteSettings()
                    }

                    val contactsPermDenied = isPermanentlyDenied(Manifest.permission.READ_CONTACTS, permStatus.contactsGranted)
                    PermissionRow(
                        title = "Contacts",
                        granted = permStatus.contactsGranted,
                        permanentlyDenied = contactsPermDenied
                    ) {
                        if (contactsPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    }

                    val calendarPermDenied = isPermanentlyDenied(Manifest.permission.WRITE_CALENDAR, permStatus.calendarGranted)
                    PermissionRow(
                        title = "Calendar",
                        granted = permStatus.calendarGranted,
                        permanentlyDenied = calendarPermDenied
                    ) {
                        if (calendarPermDenied) viewModel.openAppSettings()
                        else permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }

                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onRequestScreenCapture, modifier = Modifier.fillMaxWidth()) {
                        Text("Allow Screen Capture")
                    }
                }
            }

            // Concise Privacy Summary & Developer / Debug Mode Toggle
            item {
                GlassCard(accentBorder = EmeraldSuccess.copy(alpha = 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = EmeraldSuccess)
                        Text("On-Device Privacy & Advanced", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Voice audio, screenshots, personal memory, and command history stay on your device. Sensitive actions require your confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitchRow(
                        title = "Developer / Debug Mode",
                        subtitle = "Show technical diagnostics, ABI/RAM telemetry, locale codes, and tool schemas",
                        checked = settings.developerModeEnabled,
                        onCheckedChange = { viewModel.setDeveloperModeEnabled(it) },
                        switchModifier = Modifier.testTag("developer_mode_switch")
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
    onCheckedChange: (Boolean) -> Unit,
    switchModifier: Modifier = Modifier
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = switchModifier
        )
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
