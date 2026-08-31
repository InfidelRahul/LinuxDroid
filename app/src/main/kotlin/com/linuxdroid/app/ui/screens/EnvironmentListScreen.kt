package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentConfiguration
import com.linuxdroid.core.model.EnvironmentState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentListScreen(
    navController: NavController,
    viewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val installProgress by viewModel.installProgress.collectAsState()
    val installStatusText by viewModel.installStatusText.collectAsState()
    val installerLogs by viewModel.installerLogs.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var envToDelete by remember { mutableStateOf<Environment?>(null) }
    var envForDesktop by remember { mutableStateOf<Environment?>(null) }
    var envForSettings by remember { mutableStateOf<Environment?>(null) }
    var envForStorage by remember { mutableStateOf<Environment?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Environments") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Diagnostics.route) }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Diagnostics")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Environment")
            }
        }
    ) { padding ->
        if (environments.isEmpty()) {
            EmptyEnvironmentsPlaceholder(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreateClick = { showCreateDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(environments, key = { it.id.value }) { env ->
                    val progress = installProgress[env.id.value]
                    val statusText = installStatusText[env.id.value]
                    val logs = installerLogs[env.id.value] ?: emptyList()

                    EnvironmentCard(
                        environment = env,
                        progress = progress,
                        statusText = statusText,
                        logs = logs,
                        onStartClick = { viewModel.startEnvironment(env) },
                        onStopClick = { viewModel.stopEnvironment(env) },
                        onRestartClick = { viewModel.restartEnvironment(env) },
                        onInstallClick = { viewModel.installRootfs(env) },
                        onShellClick = {
                            navController.navigate(Screen.Terminal.route(env.id.value))
                        },
                        onDesktopClick = { envForDesktop = env },
                        onSettingsClick = { envForSettings = env },
                        onStorageClick = { envForStorage = env },
                        onDiagnosticsClick = { navController.navigate(Screen.Diagnostics.route) },
                        onDeleteClick = { envToDelete = env },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateEnvironmentDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, dist, arch ->
                viewModel.createEnvironment(name, dist, arch, autoBootstrap = true)
                showCreateDialog = false
            }
        )
    }

    envToDelete?.let { env ->
        AlertDialog(
            onDismissRequest = { envToDelete = null },
            title = { Text("Delete Environment") },
            text = { Text("Are you sure you want to delete '${env.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEnvironment(env)
                        envToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { envToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    envForDesktop?.let { env ->
        AlertDialog(
            onDismissRequest = { envForDesktop = null },
            title = { Text("Linux Desktop GUI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The rootless Linux environment is running.")
                    Text(
                        "To start a graphical desktop (e.g. XFCE / Wayland), open the interactive shell and install a desktop environment:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        color = Color(0xFF1E1E1E),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "apt update && apt install -y xfce4",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val id = env.id.value
                    envForDesktop = null
                    navController.navigate(Screen.Terminal.route(id))
                }) {
                    Text("Open Shell")
                }
            },
            dismissButton = {
                TextButton(onClick = { envForDesktop = null }) {
                    Text("Close")
                }
            }
        )
    }

    envForSettings?.let { env ->
        EnvironmentSettingsDialog(
            environment = env,
            onDismiss = { envForSettings = null },
            onSave = { updatedConfig ->
                viewModel.updateConfiguration(env, updatedConfig)
                envForSettings = null
            }
        )
    }

    envForStorage?.let { env ->
        EnvironmentStorageDialog(
            environment = env,
            onDismiss = { envForStorage = null }
        )
    }
}

@Composable
private fun EmptyEnvironmentsPlaceholder(
    modifier: Modifier = Modifier,
    onCreateClick: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "No Linux Environments",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Create a persistent Debian or Ubuntu Linux environment to run packages and terminal tools directly on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create Environment")
            }
        }
    }
}

@Composable
private fun EnvironmentCard(
    environment: Environment,
    progress: Float?,
    statusText: String?,
    logs: List<String> = emptyList(),
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onRestartClick: () -> Unit,
    onInstallClick: () -> Unit,
    onShellClick: () -> Unit,
    onDesktopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onStorageClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = environment.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${environment.distribution.displayName} • ${environment.architecture.abiName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateChip(state = environment.state)
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (environment.state == EnvironmentState.INSTALLING) {
                Spacer(modifier = Modifier.height(12.dp))
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText ?: "Downloading and setting up rootfs…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Ubuntu installer style live terminal output
                InstallerTerminalConsole(logs = logs)
            }

            environment.failureMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary State Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (environment.state) {
                    EnvironmentState.CREATED -> {
                        Button(onClick = onInstallClick) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("INSTALL")
                        }
                    }
                    EnvironmentState.READY, EnvironmentState.STOPPED -> {
                        Button(onClick = onStartClick) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("START")
                        }
                        OutlinedButton(onClick = onShellClick) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN SHELL")
                        }
                    }
                    EnvironmentState.RUNNING -> {
                        Button(
                            onClick = onShellClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN SHELL")
                        }
                        Button(
                            onClick = onDesktopClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                        ) {
                            Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN DESKTOP")
                        }
                        OutlinedButton(onClick = onRestartClick) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RESTART")
                        }
                        OutlinedButton(
                            onClick = onStopClick,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("STOP")
                        }
                    }
                    EnvironmentState.STARTING, EnvironmentState.STOPPING -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (environment.state == EnvironmentState.STARTING) "Starting…" else "Stopping…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    EnvironmentState.FAILED -> {
                        Button(onClick = onRestartClick) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RETRY")
                        }
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            // Subsystem actions: Settings, Storage, Logs/Diagnostics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Settings", fontSize = 12.sp)
                }

                TextButton(onClick = onStorageClick) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Storage", fontSize = 12.sp)
                }

                TextButton(onClick = onDiagnosticsClick) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Logs / Diagnostics", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: EnvironmentState) {
    val (containerColor, contentColor, label) = when (state) {
        EnvironmentState.RUNNING -> Triple(
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            "Running"
        )
        EnvironmentState.STARTING -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Starting"
        )
        EnvironmentState.STOPPING -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Stopping"
        )
        EnvironmentState.INSTALLING -> Triple(
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            "Installing"
        )
        EnvironmentState.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Failed"
        )
        EnvironmentState.READY -> Triple(
            Color(0xFFF3E5F5),
            Color(0xFF6A1B9A),
            "Ready"
        )
        EnvironmentState.STOPPED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Stopped"
        )
        EnvironmentState.CREATED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Created"
        )
        EnvironmentState.RECOVERING -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Recovering"
        )
        EnvironmentState.DELETING -> Triple(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            "Deleting"
        )
        EnvironmentState.CLONING -> Triple(
            Color(0xFFE0F7FA),
            Color(0xFF00838F),
            "Cloning"
        )
        EnvironmentState.RESETTING -> Triple(
            Color(0xFFFFF8E1),
            Color(0xFFF57F17),
            "Resetting"
        )
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEnvironmentDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, distribution: Distribution, architecture: Architecture) -> Unit,
) {
    var name by remember { mutableStateOf("Debian Linux") }
    var selectedDist by remember { mutableStateOf(Distribution.DEBIAN) }
    val detectedArch = remember { Architecture.current() }
    var selectedArch by remember { mutableStateOf(detectedArch) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Linux Environment") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Environment Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column {
                    Text("Distribution", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(Distribution.DEBIAN, Distribution.UBUNTU, Distribution.KALI).forEach { dist ->
                            FilterChip(
                                selected = selectedDist == dist,
                                onClick = {
                                    selectedDist = dist
                                    if (name.isBlank() || name == "Debian Linux" || name == "Ubuntu Linux" || name == "Kali Linux Linux") {
                                        name = if (dist == Distribution.KALI) "Kali Linux" else "${dist.displayName} Linux"
                                    }
                                },
                                label = { Text(dist.displayName) }
                            )
                        }
                    }
                }

                Column {
                    Text("Architecture", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(Architecture.ARM64, Architecture.X86_64).forEach { arch ->
                            FilterChip(
                                selected = selectedArch == arch,
                                onClick = { selectedArch = arch },
                                label = { Text(arch.abiName) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, selectedDist, selectedArch) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create & Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EnvironmentSettingsDialog(
    environment: Environment,
    onDismiss: () -> Unit,
    onSave: (EnvironmentConfiguration) -> Unit,
) {
    var linuxUser by remember { mutableStateOf(environment.configuration.linuxUser) }
    var homeDir by remember { mutableStateOf(environment.configuration.homeDir) }
    var sharedStorage by remember { mutableStateOf(environment.configuration.runtime.sharedStorageEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Environment Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = linuxUser,
                    onValueChange = { linuxUser = it },
                    label = { Text("Linux User") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = homeDir,
                    onValueChange = { homeDir = it },
                    label = { Text("Home Directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enable Shared Storage", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = sharedStorage,
                        onCheckedChange = { sharedStorage = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val newConfig = environment.configuration.copy(
                    linuxUser = linuxUser.trim().ifEmpty { "root" },
                    homeDir = homeDir.trim().ifEmpty { "/root" },
                    runtime = environment.configuration.runtime.copy(
                        sharedStorageEnabled = sharedStorage
                    )
                )
                onSave(newConfig)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EnvironmentStorageDialog(
    environment: Environment,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Storage & Shared Directory") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Rootfs Location:", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = environment.rootfsPath,
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text("Shared Android Directory:", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "/sdcard/LinuxDroid -> /home/user/Android",
                        color = Color(0xFF81C784),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Text(
                    text = "Files placed in /sdcard/LinuxDroid on your device are safely accessible inside the Linux environment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun InstallerTerminalConsole(
    logs: List<String>,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp)),
        color = Color(0xFF0F172A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // Header toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF22C55E), shape = RoundedCornerShape(4.dp))
                    )
                    Text(
                        text = "INSTALLER TERMINAL OUTPUT",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = Color(0xFF334155),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${logs.size} lines",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                        },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy installer logs",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Terminal log list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = ">>> Initializing bootstrap daemon...",
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    items(logs) { line ->
                        val lineColor = when {
                            line.contains("[PASS]") || line.contains("[OK]") || line.contains("[SUCCESS]") || line.contains("[READY]") -> Color(0xFF4ADE80)
                            line.contains("[ERROR]") || line.contains("[FATAL]") || line.contains("[VALIDATE_FAIL]") -> Color(0xFFF87171)
                            line.contains("[WARN]") -> Color(0xFFFBBF24)
                            line.contains("[DOWNLOAD]") || line.contains("[EXTRACT]") || line.contains("[INIT]") || line.contains("[SOURCE]") -> Color(0xFF38BDF8)
                            line.contains("[CONFIG]") || line.contains("[VERIFY]") || line.contains("[VALIDATE]") || line.contains("[PROMOTE]") -> Color(0xFFFDE047)
                            line.startsWith("extract:") -> Color(0xFF94A3B8)
                            else -> Color(0xFFE2E8F0)
                        }

                        Text(
                            text = line,
                            color = lineColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }

                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "▋",
                                color = Color(0xFF4ADE80),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
