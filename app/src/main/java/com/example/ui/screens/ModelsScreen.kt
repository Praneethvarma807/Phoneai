package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ModelInstallStatus
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.sanitizeUiDiagnostics
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet

@Composable
fun ModelsScreen(
    viewModel: CommanderViewModel,
    modifier: Modifier = Modifier
) {
    val compat by viewModel.deviceCompatibility.collectAsStateWithLifecycle()
    val runtime by viewModel.modelRuntimeState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val installedEntities by viewModel.downloadedModels.collectAsStateWithLifecycle()
    val modelStorageUsedMb by viewModel.modelStorageUsedMb.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceCompatibility()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalLlmModel(uri)
        }
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
                Text(
                    text = "Local AI Model Manager",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Download or import offline models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Device Compatibility Summary / Developer Telemetry
            item {
                GlassCard(accentBorder = ElectricCyan.copy(alpha = 0.6f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device Compatibility", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            label = if (settings.developerModeEnabled) {
                                compat.cpuAbi
                            } else if (compat.isArm64) {
                                "Ready"
                            } else {
                                "Limited"
                            },
                            color = ElectricCyan,
                            icon = Icons.Default.Memory
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (settings.developerModeEnabled) {
                        Text(
                            text = "RAM: ${compat.availableRamMb} MB free / ${compat.totalRamMb} MB total",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Storage: ${compat.availableStorageMb} MB free • Models using $modelStorageUsedMb MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Hardware GPU/OpenCL: ${if (compat.supportsGpuAcceleration) "Detected" else "CPU fallback"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${compat.tier.label}: ${compat.tier.recommendation}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    } else {
                        Text(
                            text = "Available storage: ${compat.availableStorageMb} MB • Models using $modelStorageUsedMb MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = compat.tier.recommendation,
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan
                        )
                    }
                }
            }

            // Active Runtime & Command-Only Toggle
            item {
                GlassCard(accentBorder = if (runtime.isReady) EmeraldSuccess.copy(alpha = 0.7f) else NeuralViolet.copy(alpha = 0.6f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Model", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    runtime.isLoading -> "Loading ${runtime.loadedModelName ?: "model"}…"
                                    runtime.isReady && settings.developerModeEnabled -> "${runtime.loadedModelName} (${runtime.backendUsed})"
                                    runtime.isReady -> "${runtime.loadedModelName} (Active)"
                                    else -> "Command Engine (No model loaded)"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (runtime.isReady) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (runtime.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = ElectricCyan)
                        } else if (runtime.isReady) {
                            OutlinedButton(
                                onClick = { viewModel.unloadModel() },
                                modifier = Modifier.testTag("unload_model_button")
                            ) {
                                Text("Unload")
                            }
                        }
                    }

                    runtime.error?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = sanitizeUiDiagnostics(err, settings.developerModeEnabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = CrimsonAlert
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Command-Only Mode", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Use fast deterministic matching without loading an LLM.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.commandOnlyMode,
                            onCheckedChange = { viewModel.setCommandOnlyMode(it) },
                            modifier = Modifier.testTag("command_only_switch")
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_local_model_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import Local .litertlm Model File (Offline)")
                    }
                }
            }

            item {
                Text(
                    text = "OPEN-WEIGHT MODEL CATALOG",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Catalog Models
            items(viewModel.catalogModels, key = { it.id }) { spec ->
                val dbEntry = installedEntities.firstOrNull { it.modelId == spec.id }
                val progress = downloads[spec.id]
                val statusStr = progress?.status?.name ?: dbEntry?.status ?: ModelInstallStatus.NOT_DOWNLOADED.name
                val status = runCatching { ModelInstallStatus.valueOf(statusStr) }
                    .getOrDefault(ModelInstallStatus.NOT_DOWNLOADED)
                val isLoaded = runtime.activeModelId == spec.id && runtime.isReady
                val ramOk = compat.totalRamMb >= spec.minRamMb

                GlassCard(
                    accentBorder = if (isLoaded) EmeraldSuccess else MaterialTheme.colorScheme.outline
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${spec.family} • ${spec.expectedSizeBytes / (1024 * 1024)} MB • ${spec.license}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                        }
                        val friendlyStatus = when {
                            isLoaded -> "Active"
                            status == ModelInstallStatus.READY -> "Ready"
                            status == ModelInstallStatus.DOWNLOADING -> "Downloading"
                            status == ModelInstallStatus.PAUSED -> "Paused"
                            status == ModelInstallStatus.FAILED || status == ModelInstallStatus.CORRUPTED -> "Retry Needed"
                            else -> "Available"
                        }
                        StatusPill(
                            label = friendlyStatus,
                            color = when {
                                isLoaded -> EmeraldSuccess
                                status == ModelInstallStatus.READY -> ElectricCyan
                                status == ModelInstallStatus.FAILED || status == ModelInstallStatus.CORRUPTED -> CrimsonAlert
                                status == ModelInstallStatus.DOWNLOADING -> NeuralViolet
                                else -> AmberWarning
                            }
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(spec.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Requires ${spec.minRamMb} MB RAM, ${spec.minStorageMb} MB storage • " +
                            if (ramOk) "Compatible with your device" else "Exceeds device RAM (${compat.totalRamMb} MB)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ramOk) EmeraldSuccess else CrimsonAlert
                    )

                    if (status == ModelInstallStatus.DOWNLOADING || status == ModelInstallStatus.PAUSED) {
                        val frac = progress?.fraction ?: 0f
                        val dlMb = (progress?.bytesDownloaded ?: dbEntry?.downloadedBytes ?: 0L) / (1024 * 1024)
                        val totMb = (progress?.totalBytes ?: spec.expectedSizeBytes) / (1024 * 1024)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth(),
                            color = ElectricCyan
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${dlMb} MB / ${totMb} MB (${status.name})",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    progress?.error?.let { err ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = sanitizeUiDiagnostics(err, settings.developerModeEnabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = CrimsonAlert
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (status) {
                            ModelInstallStatus.NOT_DOWNLOADED, ModelInstallStatus.FAILED, ModelInstallStatus.CORRUPTED -> {
                                Button(
                                    onClick = { viewModel.startOrResumeDownload(spec) },
                                    enabled = ramOk,
                                    modifier = Modifier.testTag("download_${spec.id}")
                                ) {
                                    Icon(
                                        imageVector = if (status == ModelInstallStatus.FAILED) Icons.Default.Refresh else Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (status == ModelInstallStatus.FAILED) "Retry" else "Download")
                                }
                            }

                            ModelInstallStatus.DOWNLOADING -> {
                                OutlinedButton(
                                    onClick = { viewModel.pauseDownload(spec.id) },
                                    modifier = Modifier.testTag("pause_${spec.id}")
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pause")
                                }
                            }

                            ModelInstallStatus.PAUSED -> {
                                Button(
                                    onClick = { viewModel.startOrResumeDownload(spec) },
                                    modifier = Modifier.testTag("resume_${spec.id}")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Resume")
                                }
                            }

                            ModelInstallStatus.READY -> {
                                if (dbEntry != null && !isLoaded) {
                                    Button(
                                        onClick = { viewModel.loadModel(dbEntry, preferGpu = false) },
                                        enabled = ramOk && !runtime.isLoading,
                                        modifier = Modifier.testTag("load_cpu_${spec.id}")
                                    ) {
                                        Text("Load CPU")
                                    }
                                    if (compat.supportsGpuAcceleration) {
                                        OutlinedButton(
                                            onClick = { viewModel.loadModel(dbEntry, preferGpu = true) },
                                            enabled = ramOk && !runtime.isLoading
                                        ) {
                                            Text("GPU")
                                        }
                                    }
                                } else if (isLoaded) {
                                    StatusPill(label = "Loaded", color = EmeraldSuccess, icon = Icons.Default.CheckCircle)
                                }
                                IconButton(onClick = { viewModel.validateModel(spec.id) }) {
                                    Icon(Icons.Default.Verified, contentDescription = "Verify checksum", tint = ElectricCyan)
                                }
                                IconButton(
                                    onClick = { viewModel.deleteModel(spec.id) },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = CrimsonAlert)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete model")
                                }
                            }
                        }
                    }
                }
            }

            // Locally imported custom models
            val customModels = installedEntities.filter { ent ->
                viewModel.catalogModels.none { it.id == ent.modelId }
            }
            if (customModels.isNotEmpty()) {
                item {
                    Text(
                        text = "IMPORTED LOCAL MODELS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(customModels, key = { it.modelId }) { entity ->
                    val isLoaded = runtime.activeModelId == entity.modelId && runtime.isReady
                    GlassCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entity.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${entity.downloadedBytes / (1024 * 1024)} MB • ${entity.fileName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                            Row {
                                if (!isLoaded) {
                                    Button(onClick = { viewModel.loadModel(entity, preferGpu = false) }) {
                                        Text("Load")
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteModel(entity.modelId) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
