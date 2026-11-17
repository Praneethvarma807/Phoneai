package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PipelineStage
import com.example.ui.CommanderViewModel
import com.example.ui.animations.AnimatedStageIndicator
import com.example.ui.animations.CommanderAiOrb
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.sanitizeUiDiagnostics
import com.example.ui.components.statusColor
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DeepSpaceBlack
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedNavy
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.NeuralViolet

@Composable
fun DashboardScreen(
    viewModel: CommanderViewModel,
    onRequestScreenCapture: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToHub: (Int) -> Unit = {},
    onNavigateToCamera: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val execState by viewModel.executionState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelRuntimeState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val networkStatus by viewModel.networkStatus.collectAsStateWithLifecycle()
    val isFollowUpActive by viewModel.isFollowUpWindowActive.collectAsStateWithLifecycle()
    val wakeRunning by viewModel.wakeWordServiceRunning.collectAsStateWithLifecycle()
    val wakeMicLevel by viewModel.wakeWordMicLevel.collectAsStateWithLifecycle()
    val wakeBanner by viewModel.wakeBannerMessage.collectAsStateWithLifecycle()
    val ttsSpeaking by viewModel.ttsSpeaking.collectAsStateWithLifecycle()
    val ttsLevel by viewModel.ttsSpeechLevel.collectAsStateWithLifecycle()
    val recentCommands by viewModel.recentCommands.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var commandInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(execState.currentCommand, execState.finalStatus) {
        if (execState.pipelineStage != PipelineStage.IDLE || execState.finalReply.isNotBlank()) {
            listState.animateScrollToItem(4)
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceCapture()
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && execState.currentCommand.isNotBlank()) {
            viewModel.submitCommand(execState.currentCommand)
        }
    }

    val effectiveOrbState = when {
        voiceState.isListening -> OrbState.LISTENING
        ttsSpeaking -> OrbState.SPEAKING
        execState.orbState != OrbState.IDLE -> execState.orbState
        wakeRunning -> OrbState.WAKE_WORD_WAITING
        else -> OrbState.IDLE
    }

    val effectiveAudioLevel = when {
        voiceState.isListening -> voiceState.rmsNormalized
        ttsSpeaking -> ttsLevel
        wakeRunning -> wakeMicLevel
        else -> 0f
    }

    val triggerVoiceInput: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (voiceState.isListening) {
                viewModel.stopVoiceCapture()
            } else {
                viewModel.startVoiceCapture()
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Pending confirmation modal (for SMS, WhatsApp, Email, Naukri profile submit)
    execState.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAction() },
            containerColor = MidnightNavy,
            title = {
                Text(
                    text = pending.previewTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = ElectricCyan
                )
            },
            text = {
                Text(
                    text = pending.previewBody,
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmPendingAction() },
                    modifier = Modifier.testTag("confirm_action_button")
                ) {
                    Text("Confirm & Execute")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelPendingAction() },
                    modifier = Modifier.testTag("cancel_action_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dashboard_lazy_column"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Logo, Title, and Local AI Status
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_commander_emblem_1794655901481),
                                contentDescription = "AI Phone Commander emblem",
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column {
                                Text(
                                    text = "AI Commander",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Personal Assistant",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                        }

                        val statusLabel = when {
                            modelState.isLoading -> "Loading…"
                            modelState.isReady && !settings.commandOnlyMode -> "Local AI"
                            else -> "Ready"
                        }
                        val statusColor = when {
                            modelState.isReady && !settings.commandOnlyMode -> EmeraldSuccess
                            modelState.isLoading -> NeuralViolet
                            else -> ElectricCyan
                        }
                        StatusPill(
                            label = statusLabel,
                            color = statusColor,
                            icon = Icons.Default.Memory,
                            modifier = Modifier
                                .clickable { onNavigateToModels() }
                                .testTag("ai_status_pill")
                        )
                    }
                }

                // Wake Word Status Card
                item {
                    GlassCard(
                        accentBorder = if (wakeRunning) ElectricCyan.copy(alpha = 0.7f) else GlassBorder.copy(alpha = 0.5f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = wakeBanner ?: if (wakeRunning) "Wake word: Active" else "Wake word: Off",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (wakeRunning) ElectricCyan else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = settings.speechLanguage.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Settings")
                            }
                        }
                    }
                }

                // Central Animated AI Orb
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CommanderAiOrb(
                            orbState = effectiveOrbState,
                            audioLevel = effectiveAudioLevel,
                            activeCategory = execState.activeCategory,
                            onClick = triggerVoiceInput
                        )
                        Spacer(Modifier.height(6.dp))
                        val orbLabel = when {
                            voiceState.isStarting -> "Connecting voice…"
                            effectiveOrbState == OrbState.LISTENING -> "Listening…"
                            effectiveOrbState == OrbState.WAKE_WORD_WAITING -> "Say \"Hello Buddy\""
                            effectiveOrbState == OrbState.WAKE_WORD_DETECTED -> "Hello Buddy heard"
                            effectiveOrbState == OrbState.THINKING -> "Thinking…"
                            effectiveOrbState == OrbState.PROCESSING -> "Processing…"
                            effectiveOrbState == OrbState.EXECUTING -> "Running action…"
                            effectiveOrbState == OrbState.SPEAKING -> "Speaking…"
                            effectiveOrbState == OrbState.SUCCESS -> "Done"
                            effectiveOrbState == OrbState.ERROR -> "Needs attention"
                            else -> "Tap to speak"
                        }
                        Text(
                            text = orbLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (effectiveOrbState == OrbState.ERROR && voiceState.error != null) CrimsonAlert else ElectricCyan
                        )
                    }
                }

                // Speech backend & Network telemetry pills
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(
                                    label = if (settings.developerModeEnabled) {
                                        "Speech: ${voiceState.backendMode.label}"
                                    } else if (voiceState.isRecognitionAvailable) {
                                        "Voice Ready"
                                    } else {
                                        "Voice Setup Needed"
                                    },
                                    color = if (voiceState.isRecognitionAvailable) EmeraldSuccess else AmberWarning,
                                    modifier = Modifier.testTag("speech_backend_pill")
                                )
                                StatusPill(
                                    label = when {
                                        settings.offlineStrictMode -> "Offline Strict"
                                        networkStatus.isOnline -> networkStatus.transportLabel
                                        else -> "Offline Mode"
                                    },
                                    color = when {
                                        settings.offlineStrictMode -> NeuralViolet
                                        networkStatus.isOnline -> EmeraldSuccess
                                        else -> AmberWarning
                                    },
                                    icon = if (!settings.offlineStrictMode && networkStatus.isOnline) Icons.Default.Wifi else Icons.Default.CloudOff,
                                    modifier = Modifier.testTag("network_status_pill")
                                )
                                if (settings.developerModeEnabled) {
                                    voiceState.detectedSpokenLocale?.takeIf { it.isNotBlank() }?.let { loc ->
                                        StatusPill(
                                            label = "Spoken: $loc",
                                            color = NeuralViolet,
                                            modifier = Modifier.testTag("spoken_locale_pill")
                                        )
                                    }
                                }
                            }
                        }

                        if (isFollowUpActive || ttsSpeaking) {
                            Spacer(Modifier.height(6.dp))
                            AssistChip(
                                onClick = { viewModel.stopContinuousVoiceSession() },
                                label = { Text(if (ttsSpeaking) "Stop Speaking" else "Stop Follow-up Voice") },
                                leadingIcon = { Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = CrimsonAlert.copy(alpha = 0.2f),
                                    labelColor = CrimsonAlert,
                                    leadingIconContentColor = CrimsonAlert
                                ),
                                border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.6f)),
                                modifier = Modifier.testTag("stop_voice_session_chip")
                            )
                        }

                        // Active listening transcript & controls
                        AnimatedVisibility(visible = voiceState.isListening || voiceState.isStarting) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (voiceState.partialTranscript.isNotBlank()) {
                                    Text(
                                        text = "\"${voiceState.partialTranscript}\"",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.testTag("partial_transcript_text")
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.stopVoiceCapture() },
                                        modifier = Modifier.testTag("stop_voice_button")
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Done")
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.cancelVoiceCapture() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                                        modifier = Modifier.testTag("cancel_voice_button")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Cancel")
                                    }
                                }
                            }
                        }

                        // Voice Recognition error & recovery panel (e.g., Error 11, 14, 9, 10, 7)
                        AnimatedVisibility(
                            visible = !voiceState.isListening && !voiceState.isStarting && voiceState.error != null
                        ) {
                            voiceState.error?.let { voiceErr ->
                                GlassCard(
                                    accentBorder = CrimsonAlert.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .testTag("voice_error_card")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = AmberWarning,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = sanitizeUiDiagnostics(voiceErr, settings.developerModeEnabled),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (voiceState.lastErrorCode == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                            OutlinedButton(
                                                onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                                modifier = Modifier.testTag("grant_mic_button")
                                            ) {
                                                Text("Grant Microphone")
                                            }
                                        } else if (voiceState.canRetry) {
                                            OutlinedButton(
                                                onClick = { viewModel.retryVoiceCapture() },
                                                modifier = Modifier.testTag("retry_voice_button")
                                            ) {
                                                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Retry Voice")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.openOfflineSpeechSettings() },
                                            modifier = Modifier.testTag("voice_settings_cta")
                                        ) {
                                            Text("Voice Settings")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Current Execution Pipeline & Verification Result
                if (execState.pipelineStage != PipelineStage.IDLE || execState.finalReply.isNotBlank()) {
                    item {
                        val borderColor = execState.finalStatus?.let { statusColor(it) } ?: ElectricCyan
                        GlassCard(accentBorder = borderColor.copy(alpha = 0.7f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (execState.currentCommand.isNotBlank()) {
                                    Text(
                                        text = execState.currentCommand,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                Spacer(Modifier.width(8.dp))
                                StatusPill(
                                    label = execState.detectedLanguageBadge,
                                    color = NeuralViolet,
                                    icon = Icons.Default.Translate,
                                    modifier = Modifier.testTag("detected_language_pill")
                                )
                            }
                            Spacer(Modifier.height(6.dp))

                            AnimatedStageIndicator(
                                stage = execState.pipelineStage,
                                category = execState.activeCategory,
                                activeToolName = execState.activeToolName
                            )

                            if (execState.finalReply.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val status = execState.finalStatus ?: ExecutionStatus.SUCCESS
                                    Icon(
                                        imageVector = when (status) {
                                            ExecutionStatus.SUCCESS -> Icons.Default.CheckCircle
                                            ExecutionStatus.PERMISSION_REQUIRED -> Icons.Default.Security
                                            else -> Icons.Default.Warning
                                        },
                                        contentDescription = null,
                                        tint = statusColor(status),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = execState.finalReply,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("execution_reply_text")
                                    )
                                }

                                val canRetry = execState.finalStatus in setOf(
                                    ExecutionStatus.FAILED,
                                    ExecutionStatus.UNSUPPORTED
                                ) && !execState.isExecuting && execState.currentCommand.isNotBlank()
                                if (canRetry) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.retryLastCommand() },
                                        modifier = Modifier.testTag("retry_command_button")
                                    ) {
                                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retry Command")
                                    }
                                }
                            }

                            // Actionable recovery & extracted context (hide internal tool/verification telemetry)
                            if (execState.stepResults.isNotEmpty()) {
                                execState.stepResults.forEach { step ->
                                    step.data["extracted_text"]?.takeIf { it.isNotBlank() }?.let { extracted ->
                                        Spacer(Modifier.height(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = DeepSpaceBlack.copy(alpha = 0.7f),
                                            border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.4f))
                                        ) {
                                            Text(
                                                text = extracted.take(400),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }

                                    // Actionable recovery buttons for missing permissions
                                    if (step.requiresAccessibility) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { viewModel.openAccessibilitySettings() },
                                            modifier = Modifier.testTag("enable_accessibility_cta")
                                        ) {
                                            Text("Enable Accessibility Service")
                                        }
                                    }
                                    if (step.requiresWriteSettings) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { viewModel.openWriteSettings() },
                                            modifier = Modifier.testTag("enable_write_settings_cta")
                                        ) {
                                            Text("Allow Modify System Settings")
                                        }
                                    }
                                    if (step.requiresScreenCapture) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = onRequestScreenCapture,
                                            modifier = Modifier.testTag("grant_screen_capture_cta")
                                        ) {
                                            Text("Grant Screen Capture")
                                        }
                                    }
                                    if (step.missingPermissions.contains(Manifest.permission.READ_CONTACTS)) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
                                        ) {
                                            Text("Grant Contacts Permission")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Assistant Smart Hub & Vision Shortcuts
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = onNavigateToCamera,
                            label = { Text("Camera AI") },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ElevatedNavy, labelColor = ElectricCyan),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.testTag("hub_shortcut_camera")
                        )
                        AssistChip(
                            onClick = { onNavigateToHub(0) },
                            label = { Text("Memory") },
                            leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ElevatedNavy, labelColor = MaterialTheme.colorScheme.onSurface),
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.testTag("hub_shortcut_memory")
                        )
                        AssistChip(
                            onClick = { onNavigateToHub(1) },
                            label = { Text("Routines") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ElevatedNavy, labelColor = MaterialTheme.colorScheme.onSurface),
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.testTag("hub_shortcut_routines")
                        )
                        AssistChip(
                            onClick = { onNavigateToHub(2) },
                            label = { Text("Alerts") },
                            leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ElevatedNavy, labelColor = MaterialTheme.colorScheme.onSurface),
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.testTag("hub_shortcut_alerts")
                        )
                    }
                }

                // Concise Quick Commands
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Try saying",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val quickCommands = listOf(
                            "Open Chrome",
                            "Battery status",
                            "Explain my screen",
                            "Open Bluetooth settings",
                            "Create a reminder Call Alex"
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickCommands.forEachIndexed { idx, cmd ->
                                AssistChip(
                                    onClick = {
                                        if (cmd.equals("Explain my screen", ignoreCase = true)) {
                                            onRequestScreenCapture()
                                        } else {
                                            viewModel.submitCommand(cmd)
                                        }
                                    },
                                    label = { Text(cmd, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = ElevatedNavy,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = BorderStroke(1.dp, GlassBorder),
                                    modifier = Modifier.testTag("quick_chip_$idx")
                                )
                            }
                        }
                    }
                }

                // Recent Commands (deduplicated against currently shown command card)
                val visibleRecent = recentCommands.filterNot {
                    execState.finalReply.isNotBlank() &&
                        it.commandText.equals(execState.currentCommand, ignoreCase = true)
                }
                item {
                    Text(
                        text = "Recent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (visibleRecent.isEmpty()) {
                    item {
                        GlassCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = ElectricCyan
                                )
                                Text(
                                    text = "Your recent commands will appear here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(visibleRecent, key = { it.id }) { entry ->
                        val status = runCatching { ExecutionStatus.valueOf(entry.resultStatus) }
                            .getOrDefault(ExecutionStatus.FAILED)
                        GlassCard(
                            contentPadding = PaddingValues(12.dp),
                            accentBorder = statusColor(status).copy(alpha = 0.4f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.commandText,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entry.resultSummary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.submitCommand(entry.commandText) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay,
                                        contentDescription = "Repeat command",
                                        tint = ElectricCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Command Input Bar (Text + Mic + Send)
            Surface(
                color = MidnightNavy.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledIconButton(
                        onClick = triggerVoiceInput,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (voiceState.isListening) CrimsonAlert else ElevatedNavy,
                            contentColor = ElectricCyan
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("mic_button")
                    ) {
                        Icon(
                            imageVector = if (voiceState.isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (voiceState.isListening) "Stop listening" else "Voice input"
                        )
                    }

                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_input_field"),
                        placeholder = {
                            Text("Command (EN / TE / HI / TA / KN / ML)…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = DeepSpaceBlack.copy(alpha = 0.6f),
                            unfocusedContainerColor = DeepSpaceBlack.copy(alpha = 0.45f)
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (commandInput.isNotBlank() && !execState.isExecuting) {
                                    viewModel.submitCommand(commandInput)
                                    commandInput = ""
                                }
                            }
                        )
                    )

                    FilledIconButton(
                        onClick = {
                            if (commandInput.isNotBlank() && !execState.isExecuting) {
                                viewModel.submitCommand(commandInput)
                                commandInput = ""
                            }
                        },
                        enabled = commandInput.isNotBlank() && !execState.isExecuting,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = ElectricCyan,
                            contentColor = DeepSpaceBlack
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_command_button")
                    ) {
                        if (execState.isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = DeepSpaceBlack
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send command"
                            )
                        }
                    }
                }
            }
        }
    }
}
