package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.ExecutionStatus
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.components.statusColor
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    viewModel: CommanderViewModel,
    onRepeatNavigateDashboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.historyEntries.collectAsStateWithLifecycle()
    val query by viewModel.historySearch.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Command History", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Stored strictly on-device in Room SQLite (${entries.size} entries)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val exported = viewModel.exportHistoryText()
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, exported)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export Command History"))
                            }
                        },
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.testTag("export_history_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export history", tint = ElectricCyan)
                    }
                    IconButton(
                        onClick = { viewModel.clearAllHistory() },
                        enabled = entries.isNotEmpty(),
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all history", tint = CrimsonAlert)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setHistorySearch(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history_search_field"),
                placeholder = { Text("Search commands, tools, or results…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                GlassCard {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = ElectricCyan)
                        Text(
                            text = if (query.isBlank()) "No command history yet." else "No commands match \"$query\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { item ->
                        val status = runCatching { ExecutionStatus.valueOf(item.resultStatus) }
                            .getOrDefault(ExecutionStatus.FAILED)
                        GlassCard(accentBorder = statusColor(status).copy(alpha = 0.45f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(label = status.name, color = statusColor(status))
                                Text(
                                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(item.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(item.commandText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Engine: ${item.engineUsed} • Tools: ${item.selectedToolsJson}",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(item.resultSummary, style = MaterialTheme.typography.bodySmall)
                            item.failureDetails?.let { fail ->
                                Spacer(Modifier.height(4.dp))
                                Text("Failure: $fail", style = MaterialTheme.typography.labelSmall, color = CrimsonAlert)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.submitCommand(item.commandText)
                                        onRepeatNavigateDashboard()
                                    }
                                ) {
                                    Icon(Icons.Default.Replay, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Repeat")
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { viewModel.deleteHistoryItem(item.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete entry", tint = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
