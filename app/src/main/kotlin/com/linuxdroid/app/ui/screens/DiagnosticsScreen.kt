package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linuxdroid.app.ui.components.LogEditorViewerDialog
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.DiagnosticsViewModel
import com.linuxdroid.core.model.DiagnosticCheck
import com.linuxdroid.core.model.DiagnosticStatus
import com.linuxdroid.core.model.LogExportType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val environments by viewModel.environments.collectAsState()
    val selectedEnvId by viewModel.selectedEnvironmentId.collectAsState()
    val report by viewModel.report.collectAsState()
    val failureReport by viewModel.failureReport.collectAsState()
    val detailedLogs by viewModel.detailedLogs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Subsystems", "Failure Analysis", "Export Center", "Raw Stream")

    var showExportDialog by remember { mutableStateOf(false) }
    var activeEditorLog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val neuColors = NeuTheme.colors

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics & Reports", color = neuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                ),
                actions = {
                    if (selectedEnvId != null) {
                        NeuIconButton(
                            onClick = { showExportDialog = true },
                            enabled = !isLoading,
                            size = 38.dp,
                            tint = neuColors.primaryAccent,
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Export Diagnostics", modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    NeuIconButton(
                        onClick = { viewModel.refreshDiagnostics() },
                        enabled = !isLoading && selectedEnvId != null,
                        size = 38.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (environments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No environments created yet.\nCreate an environment to view diagnostics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = neuColors.textSecondary
                    )
                }
            } else {
                Text("Target Environment", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    environments.forEach { env ->
                        val isSelected = selectedEnvId == env.id.value
                        NeuButton(
                            onClick = { viewModel.selectEnvironment(env.id.value) },
                            isAccent = isSelected,
                            elevation = if (isSelected) 2.dp else 4.dp,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(env.name, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                NeuSegmentedControl(
                    items = (0 until tabs.size).toList(),
                    selectedItem = selectedTabIndex,
                    onItemSelected = { selectedTabIndex = it },
                    itemLabel = { tabs[it] },
                )

                Spacer(Modifier.height(14.dp))

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = neuColors.primaryAccent,
                        trackColor = neuColors.surfacePressed,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                when (selectedTabIndex) {
                    0 -> {
                        // Subsystem Health Checks
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
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Subsystem Health Audit", style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            NeuButton(
                                                onClick = {
                                                    val text = viewModel.getSubsystemsReportText()
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Subsystem Health Report", text))
                                                    Toast.makeText(context, "Health audit copied to clipboard", Toast.LENGTH_SHORT).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Copy", fontSize = 11.sp)
                                            }
                                            NeuButton(
                                                onClick = {
                                                    activeEditorLog = "Subsystem Health Audit" to viewModel.getSubsystemsReportText()
                                                },
                                                isAccent = true,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                            ) {
                                                Icon(Icons.Default.Code, contentDescription = "Editor", modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Editor", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }

                                items(checks) { check ->
                                    DiagnosticCheckCard(
                                        check = check,
                                        onClick = {
                                            val logText = buildString {
                                                appendLine("=== [SUBSYSTEM CHECK: ${check.name.uppercase()}] ===")
                                                appendLine("Status: ${check.status.name}")
                                                appendLine("Detail: ${check.detail}")
                                                check.recommendation?.let { appendLine("Recommendation: $it") }
                                                appendLine()
                                                appendLine("Full Audit Snapshot:")
                                                appendLine(viewModel.getSubsystemsReportText())
                                            }
                                            activeEditorLog = check.name to logText
                                        }
                                    )
                                }
                            }
                        } ?: run {
                            if (!isLoading) {
                                Text(
                                    "Tap an environment above to run diagnostics.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = neuColors.textSecondary
                                )
                            }
                        }
                    }

                    1 -> {
                        // Failure Analysis Tab
                        failureReport?.let { fail ->
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item {
                                    NeuCard(
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    if (fail.totalFailures > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = if (fail.totalFailures > 0) neuColors.error else neuColors.success,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Text(
                                                    text = if (fail.totalFailures > 0) "Failure Root Cause Analysis" else "All Subsystems Nominal",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = neuColors.textPrimary
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = fail.rootCauseSummary,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = neuColors.textSecondary
                                            )
                                            Spacer(Modifier.height(10.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Text("Failures: ${fail.totalFailures}", fontSize = 12.sp, color = neuColors.error, fontWeight = FontWeight.SemiBold)
                                                Text("Unique Signatures: ${fail.uniqueSignaturesCount}", fontSize = 12.sp, color = neuColors.primaryAccent, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                NeuButton(
                                                    onClick = {
                                                        activeEditorLog = "Failure Analysis Report" to viewModel.getFailureReportText(false)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    isAccent = true,
                                                ) {
                                                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Open Editor", fontSize = 11.sp)
                                                }
                                                NeuButton(
                                                    onClick = {
                                                        val text = viewModel.getFailureReportText(false)
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Failure Report", text))
                                                        Toast.makeText(context, "Failure report copied to clipboard", Toast.LENGTH_SHORT).show()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Copy Text", fontSize = 11.sp)
                                                }
                                                NeuButton(
                                                    onClick = {
                                                        val jsonText = viewModel.getFailureReportText(true)
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Failure Report JSON", jsonText))
                                                        Toast.makeText(context, "Failure report JSON copied", Toast.LENGTH_SHORT).show()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Copy JSON", fontSize = 11.sp)
                                                }
                                                NeuButton(
                                                    onClick = { viewModel.exportLog(context, LogExportType.FAILURE_REPORT_COMPACT, false) },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                if (fail.causalChains.isNotEmpty()) {
                                    item {
                                        Text("Correlated Failure Chains", style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
                                    }
                                    items(fail.causalChains) { chain ->
                                        val chainText = buildString {
                                            appendLine("=== [FAILURE CHAIN: ${chain.firstOrNull()?.correlationId ?: "unknown"}] ===")
                                            chain.forEachIndexed { i, ev ->
                                                appendLine("${i + 1}. [${ev.category.name}] Source: ${ev.source}")
                                                appendLine("   Message: ${ev.message}")
                                                ev.errnoName?.let { appendLine("   Errno: $it (${ev.errno})") }
                                                ev.rawAddress?.let { appendLine("   Address: $it") }
                                                if (ev.contextBefore.isNotEmpty()) {
                                                    appendLine("   Context:")
                                                    ev.contextBefore.takeLast(3).forEach { appendLine("     | $it") }
                                                }
                                                appendLine()
                                            }
                                        }

                                        NeuCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    activeEditorLog = "Causal Chain: ${chain.firstOrNull()?.correlationId}" to chainText
                                                },
                                            isInset = true,
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "Chain: ${chain.firstOrNull()?.correlationId ?: "unknown"}",
                                                        fontFamily = SfMono,
                                                        fontSize = 11.sp,
                                                        color = neuColors.primaryAccent
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        NeuIconButton(
                                                            onClick = {
                                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(ClipData.newPlainText("Chain Log", chainText))
                                                                Toast.makeText(context, "Chain log copied", Toast.LENGTH_SHORT).show()
                                                            },
                                                            size = 24.dp,
                                                            tint = neuColors.textMuted
                                                        ) {
                                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                                        }
                                                        NeuIconButton(
                                                            onClick = {
                                                                activeEditorLog = "Chain ${chain.firstOrNull()?.correlationId}" to chainText
                                                            },
                                                            size = 24.dp,
                                                            tint = neuColors.primaryAccent
                                                        ) {
                                                            Icon(Icons.Default.Code, contentDescription = "Open in Editor", modifier = Modifier.size(13.dp))
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                chain.forEachIndexed { i, ev ->
                                                    Text(
                                                        "${i + 1}. [${ev.category.name}] ${ev.message}",
                                                        fontFamily = SfMono,
                                                        fontSize = 11.sp,
                                                        color = neuColors.textPrimary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (fail.aggregatedFailures.isNotEmpty()) {
                                    item {
                                        Text("Deduplicated Error Counts", style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
                                    }
                                    items(fail.aggregatedFailures) { agg ->
                                        NeuCard(
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "[${agg.category.name}] ${agg.source}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        color = neuColors.error
                                                    )
                                                    Text(
                                                        text = agg.message.take(80),
                                                        fontSize = 11.sp,
                                                        fontFamily = SfMono,
                                                        color = neuColors.textSecondary
                                                    )
                                                }
                                                Surface(
                                                    color = neuColors.surfacePressed,
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text(
                                                        text = "x${agg.count}",
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = neuColors.primaryAccent
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // Export Center Tab with 1-click Copy & Editor opening
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Text("Log & Report Types", style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
                            }
                            items(LogExportType.values()) { exportType ->
                                NeuCard(
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(exportType.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = neuColors.textPrimary)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            exportType.description,
                                            fontSize = 12.sp,
                                            color = neuColors.textSecondary
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // 1. Open in Editor
                                            NeuButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        val logText = viewModel.getLogContent(exportType, false)
                                                        activeEditorLog = exportType.displayName to logText
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(10.dp),
                                                isAccent = true,
                                            ) {
                                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Editor", fontSize = 11.sp)
                                            }

                                            // 2. Copy Plain Text
                                            NeuButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        val logText = viewModel.getLogContent(exportType, false)
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText(exportType.displayName, logText))
                                                        Toast.makeText(context, "${exportType.displayName} copied to clipboard", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Copy", fontSize = 11.sp)
                                            }

                                            // 3. Copy JSON (for supported formats)
                                            if (exportType == LogExportType.FAILURE_REPORT_COMPACT ||
                                                exportType == LogExportType.FAILURE_REPORT_DEVELOPER ||
                                                exportType == LogExportType.TERMINAL_FAILURE_LOG) {
                                                NeuButton(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            val jsonText = viewModel.getLogContent(exportType, true)
                                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            clipboard.setPrimaryClip(ClipData.newPlainText("${exportType.displayName} JSON", jsonText))
                                                            Toast.makeText(context, "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                ) {
                                                    Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("JSON", fontSize = 11.sp)
                                                }
                                            }

                                            // 4. Share Intent
                                            NeuButton(
                                                onClick = { viewModel.exportLog(context, exportType, false) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                shape = RoundedCornerShape(10.dp),
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // Raw Stream Tab
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Full Diagnostic Trace", style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    NeuButton(
                                        onClick = {
                                            detailedLogs?.let { text ->
                                                activeEditorLog = "Raw Diagnostic Stream" to text
                                            }
                                        },
                                        enabled = detailedLogs != null,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        isAccent = true,
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = "Editor", modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Editor", fontSize = 11.sp)
                                    }
                                    NeuButton(
                                        onClick = {
                                            detailedLogs?.let { text ->
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("LinuxDroid Diagnostic Log", text))
                                                Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        enabled = detailedLogs != null,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", fontSize = 11.sp)
                                    }
                                    NeuButton(
                                        onClick = { viewModel.exportLog(context, LogExportType.FULL_LOGS, false) },
                                        enabled = detailedLogs != null,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            NeuCard(
                                isInset = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                                    .clickable {
                                        detailedLogs?.let { text ->
                                            activeEditorLog = "Raw Diagnostic Stream" to text
                                        }
                                    }
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
                                                fontFamily = SfMono,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                                color = neuColors.textSecondary,
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

    // Modal Log Editor Viewer Dialog
    activeEditorLog?.let { (title, content) ->
        LogEditorViewerDialog(
            title = title,
            logContent = content,
            onDismiss = { activeEditorLog = null },
            onShare = {
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, content)
                    putExtra(android.content.Intent.EXTRA_TITLE, title)
                    type = "text/plain"
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share $title"))
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = neuColors.background,
            title = { Text("Export Diagnostics & Logs", color = neuColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogExportType.values().forEach { exportType ->
                        NeuButton(
                            onClick = {
                                showExportDialog = false
                                viewModel.exportLog(context, exportType, false)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(exportType.displayName, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                NeuButton(
                    onClick = { showExportDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun DiagnosticCheckCard(
    check: DiagnosticCheck,
    onClick: (() -> Unit)? = null,
) {
    NeuCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        DiagnosticCheckRow(check = check, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun DiagnosticCheckRow(check: DiagnosticCheck, modifier: Modifier = Modifier) {
    val neuColors = NeuTheme.colors
    val icon = when (check.status) {
        DiagnosticStatus.OK -> Icons.Default.CheckCircle
        DiagnosticStatus.WARNING -> Icons.Default.Warning
        DiagnosticStatus.ERROR -> Icons.Default.Error
        DiagnosticStatus.UNKNOWN, DiagnosticStatus.NOT_APPLICABLE -> Icons.AutoMirrored.Filled.Help
    }
    val tint = when (check.status) {
        DiagnosticStatus.OK -> neuColors.success
        DiagnosticStatus.WARNING -> neuColors.warning
        DiagnosticStatus.ERROR -> neuColors.error
        DiagnosticStatus.UNKNOWN, DiagnosticStatus.NOT_APPLICABLE -> neuColors.textMuted
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = check.status.name, tint = tint, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(check.name, style = MaterialTheme.typography.titleSmall, color = neuColors.textPrimary)
            if (check.detail.isNotBlank()) {
                Text(
                    check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.textSecondary,
                )
            }
            check.recommendation?.let { rec ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "Recommendation: $rec",
                    style = MaterialTheme.typography.labelSmall,
                    color = neuColors.primaryAccent,
                )
            }
        }
    }
}
