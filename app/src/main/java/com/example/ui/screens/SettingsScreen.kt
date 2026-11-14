package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.CommanderAccessibilityService
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val wakeRunning by viewModel.wakeWordServiceRunning.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val ttsVoices by viewModel.ttsVoices.collectAsStateWithLifecycle()
    val accessibilityActive by CommanderAccessibilityService.activeService.collectAsStateWithLifecycle()

    var voiceMenuExpanded by remember { mutableStateOf(false) }

    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
    val contactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val calendarGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val writeSettingsGranted = Settings.System.canWrite(context)

    val wakeMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.toggleWakeWord(true)
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val kwsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importKwsModel(uri)
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
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
                                        if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
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
                            label = if (micGranted) "Mic Granted" else "Mic Needed",
                            color = if (micGranted) EmeraldSuccess else AmberWarning,
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
                        text = "Battery & Background Note: Wake word listening runs in an explicit foreground service with a persistent notification. Audio stays in RAM and is never recorded to disk or uploaded. OEM battery savers may restrict background mic capture when force-stopped or after reboot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Voice & Language Section
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = NeuralViolet)
                        Text("Voice & Multilingual Recognition", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpeechLanguageOption.entries.take(2).forEach { lang ->
                            FilterChip(
                                selected = settings.speechLanguage == lang,
                                onClick = { viewModel.setSpeechLanguage(lang) },
                                label = { Text(lang.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpeechLanguageOption.entries.drop(2).forEach { lang ->
                            FilterChip(
                                selected = settings.speechLanguage == lang,
                                onClick = { viewModel.setSpeechLanguage(lang) },
                                label = { Text(lang.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    voiceState.missingPackWarning?.let { warn ->
                        Spacer(Modifier.height(6.dp))
                        Text(warn, style = MaterialTheme.typography.bodySmall, color = AmberWarning)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { viewModel.openOfflineSpeechSettings() }) {
                        Text("Manage Offline Language Packs")
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

            // Permission Center
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = ElectricCyan)
                        Text("Permission & Security Center", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    PermissionRow("Microphone", micGranted) {
                        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionRow("Notifications", notifGranted) {
                            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                    PermissionRow("Accessibility Automation", accessibilityActive != null) {
                        viewModel.openAccessibilitySettings()
                    }
                    PermissionRow("Modify Brightness (Write Settings)", writeSettingsGranted) {
                        viewModel.openWriteSettings()
                    }
                    PermissionRow("Contacts (Drafting & Lookup)", contactsGranted) {
                        permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    }
                    PermissionRow("Calendar Events", calendarGranted) {
                        permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
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
                text = if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) EmeraldSuccess else AmberWarning
            )
        }
        OutlinedButton(onClick = onAction) {
            Text(if (granted) "Review" else "Grant")
        }
    }
}
