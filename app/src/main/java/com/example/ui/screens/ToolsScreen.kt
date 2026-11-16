package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.CommanderAccessibilityService
import com.example.data.local.LocalReminderEntity
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.NeuralViolet

@Composable
fun ToolsScreen(
    viewModel: CommanderViewModel,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accessibilityActive by CommanderAccessibilityService.activeService.collectAsStateWithLifecycle()
    val notes by viewModel.localNotes.collectAsStateWithLifecycle()
    val reminders by viewModel.localReminders.collectAsStateWithLifecycle()
    val authorizedDocs by viewModel.authorizedDocuments.collectAsStateWithLifecycle()
    val docPreview by viewModel.documentPreview.collectAsStateWithLifecycle()

    var showReminderDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<LocalReminderEntity?>(null) }
    var reminderTitleInput by remember { mutableStateOf("") }
    var reminderDelayInput by remember { mutableStateOf("15") }
    var reminderNotesInput by remember { mutableStateOf("") }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.authorizeDocumentFile(uri)
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.authorizeDocumentFolder(treeUri)
        }
    }

    if (showReminderDialog) {
        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            containerColor = MidnightNavy,
            title = {
                Text(
                    if (editingReminder == null) "Schedule New Reminder" else "Edit & Reschedule Reminder",
                    color = ElectricCyan
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reminderTitleInput,
                        onValueChange = { reminderTitleInput = it },
                        label = { Text("Reminder Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("reminder_title_input")
                    )
                    OutlinedTextField(
                        value = reminderDelayInput,
                        onValueChange = { ch -> reminderDelayInput = ch.filter { it.isDigit() } },
                        label = { Text("Trigger in (minutes)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("reminder_delay_input")
                    )
                    OutlinedTextField(
                        value = reminderNotesInput,
                        onValueChange = { reminderNotesInput = it },
                        label = { Text("Optional notes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = reminderDelayInput.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
                        val target = editingReminder
                        if (target == null) {
                            viewModel.createReminder(reminderTitleInput, mins, reminderNotesInput)
                        } else {
                            viewModel.rescheduleReminder(target.id, reminderTitleInput, mins, reminderNotesInput)
                        }
                        showReminderDialog = false
                    },
                    enabled = reminderTitleInput.isNotBlank(),
                    modifier = Modifier.testTag("save_reminder_button")
                ) {
                    Text(if (editingReminder == null) "Schedule" else "Reschedule")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    docPreview?.let { (docName, textBody) ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDocumentPreview() },
            containerColor = MidnightNavy,
            title = { Text(docName, color = ElectricCyan, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(textBody, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDocumentPreview() }) {
                    Text("Close")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("tools_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Automation & Tool Registry", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "${viewModel.registeredToolSchemas.size} schema-validated Android tools ready for local planning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Browser, Screen & SAF Document Controls
            item {
                GlassCard(accentBorder = ElectricCyan.copy(alpha = 0.6f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Browser, Screen & SAF Access", style = MaterialTheme.typography.titleMedium)
                        StatusPill(
                            label = if (accessibilityActive != null) "ACTIVE" else "OFF",
                            color = if (accessibilityActive != null) EmeraldSuccess else AmberWarning,
                            icon = Icons.Default.AccessibilityNew
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Accessibility reads visible text and interacts with UI controls. Document & folder pickers use zero-permission Storage Access Framework (SAF).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.openAccessibilitySettings() },
                            modifier = Modifier.weight(1f).testTag("tools_accessibility_button")
                        ) {
                            Text(if (accessibilityActive != null) "Accessibility" else "Enable Access.", maxLines = 1)
                        }
                        Button(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier.weight(1f).testTag("tools_explain_screen_button")
                        ) {
                            Icon(Icons.Default.Screenshot, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Explain Screen", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openDocLauncher.launch(arrayOf("application/pdf", "text/*", "*/*")) },
                            modifier = Modifier.weight(1f).testTag("pick_saf_file_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add File", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { openFolderLauncher.launch(null) },
                            modifier = Modifier.weight(1f).testTag("pick_saf_folder_button")
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Folder", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            editingReminder = null
                            reminderTitleInput = ""
                            reminderDelayInput = "15"
                            reminderNotesInput = ""
                            showReminderDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().testTag("new_reminder_button")
                    ) {
                        Icon(Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Schedule Reminder (AlarmManager / WorkManager)")
                    }
                }
            }

            // SAF Authorized Documents & Folders
            if (authorizedDocs.isNotEmpty()) {
                item {
                    Text(
                        text = "AUTHORIZED SAF FILES & FOLDERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(authorizedDocs, key = { "saf_${it.uriString}" }) { doc ->
                    GlassCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(doc.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (doc.isTree) "Authorized SAF Folder" else "Authorized SAF File (${doc.mimeType})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!doc.isTree) {
                                    IconButton(onClick = { viewModel.previewAuthorizedDocument(doc) }) {
                                        Icon(Icons.Default.Visibility, contentDescription = "Preview text document", tint = NeuralViolet)
                                    }
                                    IconButton(
                                        onClick = {
                                            runCatching {
                                                val uri = Uri.parse(doc.uriString)
                                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, doc.mimeType.ifBlank { "*/*" })
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(viewIntent)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Open external viewer", tint = ElectricCyan)
                                    }
                                }
                                IconButton(onClick = { viewModel.revokeDocumentAccess(doc.uriString) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Revoke SAF access", tint = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
            }

            // On-Device Reminders & Notes
            if (reminders.isNotEmpty() || notes.isNotEmpty()) {
                item {
                    Text(
                        text = "LOCAL NOTES & REMINDERS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(reminders, key = { "rem_${it.id}" }) { reminder ->
                    GlassCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(
                                    checked = reminder.isCompleted,
                                    onCheckedChange = { viewModel.toggleReminderCompleted(reminder.id, it) }
                                )
                                Column {
                                    Text(reminder.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(reminder.triggerAtMillis)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan
                                    )
                                }
                            }
                            Row {
                                IconButton(
                                    onClick = {
                                        editingReminder = reminder
                                        reminderTitleInput = reminder.title
                                        reminderDelayInput = "15"
                                        reminderNotesInput = reminder.notes
                                        showReminderDialog = true
                                    }
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit reminder", tint = ElectricCyan)
                                }
                                IconButton(onClick = { viewModel.deleteReminder(reminder.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete reminder", tint = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
                items(notes, key = { "note_${it.id}" }) { note ->
                    GlassCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = NeuralViolet)
                                Column {
                                    Text(note.title, style = MaterialTheme.typography.titleSmall)
                                    Text(note.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { viewModel.deleteNote(note.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete note", tint = CrimsonAlert)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "REGISTERED TOOL SCHEMAS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(viewModel.registeredToolSchemas, key = { it.name }) { schema ->
                GlassCard(contentPadding = PaddingValues(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = schema.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (schema.requiresConfirmation) {
                                StatusPill(label = "CONFIRM GATE", color = NeuralViolet, icon = Icons.Default.Lock)
                            } else {
                                StatusPill(label = schema.category, color = EmeraldSuccess, icon = Icons.Default.Check)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(schema.description, style = MaterialTheme.typography.bodySmall)
                    if (schema.parameters.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Params: ${schema.parameters}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (schema.requiredPermissions.isNotEmpty()) {
                        Text(
                            text = "Permissions: ${schema.requiredPermissions.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberWarning
                        )
                    }
                }
            }
        }
    }
}
