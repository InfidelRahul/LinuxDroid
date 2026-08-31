package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linuxdroid.app.ui.viewmodel.DiagnosticsViewModel
import com.linuxdroid.core.model.DiagnosticCheck
import com.linuxdroid.core.model.DiagnosticStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val environments by viewModel.environments.collectAsState()
    val selectedEnvId by viewModel.selectedEnvironmentId.collectAsState()
    val report by viewModel.report.collectAsState()
    val detailedLogs by viewModel.detailedLogs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Subsystem Checks", "Detailed Runtime Logs")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subsystem Diagnostics") },
                actions = {
                    if (selectedEnvId != null) {
                        IconButton(
                            onClick = { viewModel.exportLogs(context) },
                            enabled = !isLoading,
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Export & Share Logs")
                        }
                    }
                    IconButton(
                        onClick = { viewModel.refreshDiagnostics() },
                        enabled = !isLoading && selectedEnvId != null,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (environments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No environments created yet.\nCreate an environment to view diagnostics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text("Select Environment", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    environments.forEach { env ->
                        FilterChip(
                            selected = selectedEnvId == env.id.value,
                            onClick = { viewModel.selectEnvironment(env.id.value) },
                            label = { Text(env.name) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                TabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }

                if (selectedTabIndex == 0) {
                    // Subsystem checks tab
                    report?.let { rep ->
                        val checks = listOf(
                            rep.runtime,
                            rep.filesystem,
                            rep.linuxUserspace,
                            rep.wayland,
                            rep.xwayland,
                            rep.gpu,
                            rep.audio,
                            rep.network,
                            rep.sharedStorage,
                            rep.resources,
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(checks) { check ->
                                DiagnosticCheckCard(check = check)
                            }
                        }
                    } ?: run {
                        if (!isLoading) {
                            Text(
                                "Tap an environment above to run diagnostics.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Detailed Runtime Logs tab
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Full Diagnostic Trace", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        detailedLogs?.let { text ->
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDroid Diagnostic Log", text))
                                            Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    enabled = detailedLogs != null,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { viewModel.exportLogs(context) },
                                    enabled = detailedLogs != null,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            SelectionContainer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                            ) {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        Text(
                                            text = detailedLogs ?: "No logs available. Run diagnostics to populate.",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = Color(0xFF94A3B8),
                                        )
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

@Composable
fun DiagnosticCheckCard(check: DiagnosticCheck) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        DiagnosticCheckRow(check = check, modifier = Modifier.padding(12.dp))
    }
}

@Composable
fun DiagnosticCheckRow(check: DiagnosticCheck, modifier: Modifier = Modifier) {
    val (icon, tint) = when (check.status) {
        DiagnosticStatus.OK -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        DiagnosticStatus.WARNING -> Icons.Default.Warning to Color(0xFFFFC107)
        DiagnosticStatus.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        DiagnosticStatus.UNKNOWN, DiagnosticStatus.NOT_APPLICABLE -> Icons.AutoMirrored.Filled.Help to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = check.status.name, tint = tint, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(check.name, style = MaterialTheme.typography.titleSmall)
            if (check.detail.isNotBlank()) {
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            check.recommendation?.let { rec ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "Recommendation: $rec",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
