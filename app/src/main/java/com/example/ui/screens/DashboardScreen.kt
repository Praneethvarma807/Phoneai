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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val execState by viewModel.executionState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelRuntimeState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceListeningState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
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
            listState.animateScrollToItem(2)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = pending.previewBody,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Tools: ${pending.plan.toolCalls.joinToString { it.tool }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                                    text = "AI PHONE COMMANDER",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "100% On-Device • Zero API Keys",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                        }

                        val statusLabel = when {
                            modelState.isLoading -> "Loading AI…"
                            modelState.isReady && !settings.commandOnlyMode -> "${modelState.backendUsed} LLM"
                            else -> "Deterministic"
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

                // Wake word & Greeting Banner
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
                                    text = wakeBanner ?: when {
                                        wakeRunning -> "Waiting for \"Hello Buddy\""
                                        else -> "Ready. Say or type any phone command."
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (wakeRunning) ElectricCyan else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Language: ${settings.speechLanguage.displayName} • ${if (wakeRunning) "Wake word active" else "Wake word off"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(if (wakeRunning) "Wake Settings" else "Enable Wake")
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
                        Text(
                            text = when (effectiveOrbState) {
                                OrbState.LISTENING -> "Listening… ${voiceState.durationMs / 1000}s"
                                OrbState.WAKE_WORD_WAITING -> "Listening locally for \"Hello Buddy\""
                                OrbState.WAKE_WORD_DETECTED -> "Hello Buddy detected!"
                                OrbState.THINKING -> "Local AI planning action…"
                                OrbState.PROCESSING -> "Understanding command…"
                                OrbState.EXECUTING -> "Executing ${execState.activeToolName}…"
                                OrbState.SPEAKING -> "Speaking response…"
                                OrbState.SUCCESS -> "Verified & Completed"
                                OrbState.ERROR -> "Attention Required"
                                OrbState.IDLE -> "Tap Orb to Speak"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )

                        // Active listening transcript & controls
                        AnimatedVisibility(visible = voiceState.isListening) {
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
                                        color = MaterialTheme.colorScheme.onBackground
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
                                        text = "COMMAND: ${execState.currentCommand}",
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

                            // Step-by-step verification notes
                            if (execState.stepResults.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                execState.stepResults.forEach { step ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${step.toolName} • ${step.verificationNote}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = statusColor(step.status),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Contextual extracted screen data if available
                                    step.data["extracted_text"]?.takeIf { it.isNotBlank() }?.let { extracted ->
                                        Spacer(Modifier.height(6.dp))
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

                // Quick Automation Chips (English + Indic Multilingual + Multi-step)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "QUICK COMMANDS (MULTILINGUAL)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val quickCommands = listOf(
                            "Open Chrome",
                            "YouTube lo Java tutorials search chey",
                            "YouTube पर AI videos search करो",
                            "Chrome திற",
                            "Open Bluetooth settings",
                            "Explain my screen",
                            "Create a reminder Call Alex",
                            "Open Naukri and search for SAP FICO jobs in Hyderabad",
                            "Search files for resume"
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

                // Recent Commands
                item {
                    Text(
                        text = "RECENT COMMANDS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (recentCommands.isEmpty()) {
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
                                    text = "No commands executed yet. Tap the Orb or type a command below — everything runs locally on your phone.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(recentCommands, key = { it.id }) { entry ->
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
