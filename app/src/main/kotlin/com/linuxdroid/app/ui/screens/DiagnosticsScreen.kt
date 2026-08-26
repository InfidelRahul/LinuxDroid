package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linuxdroid.app.ui.viewmodel.DiagnosticsViewModel
import com.linuxdroid.core.model.DiagnosticCheck
import com.linuxdroid.core.model.DiagnosticStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val selectedEnvId by viewModel.selectedEnvironmentId.collectAsState()
    val report by viewModel.report.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subsystem Diagnostics") },
                actions = {
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

                Spacer(Modifier.height(16.dp))

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }

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
