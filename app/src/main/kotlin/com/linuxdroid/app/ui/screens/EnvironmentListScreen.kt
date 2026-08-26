package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
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
    var showCreateDialog by remember { mutableStateOf(false) }
    var envToDelete by remember { mutableStateOf<Environment?>(null) }
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(environments, key = { it.id.value }) { env ->
                    val progress = installProgress[env.id.value]
                    val statusText = installStatusText[env.id.value]

                    EnvironmentCard(
                        environment = env,
                        progress = progress,
                        statusText = statusText,
                        onStartClick = { viewModel.startEnvironment(env) },
                        onStopClick = { viewModel.stopEnvironment(env) },
                        onInstallClick = { viewModel.installRootfs(env) },
                        onTerminalClick = {
                            navController.navigate(Screen.Terminal.route(env.id.value))
                        },
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
            text = { Text("Are you sure you want to remove '${env.name}'? (The rootfs files will be safely unlinked from app storage).") },
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
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onInstallClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        OutlinedButton(onClick = onTerminalClick) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("SHELL")
                        }
                    }
                    EnvironmentState.RUNNING -> {
                        Button(
                            onClick = onTerminalClick,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("OPEN SHELL")
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
                        Button(onClick = onInstallClick) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("RETRY INSTALL")
                        }
                    }
                    else -> {}
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
                        listOf(Distribution.DEBIAN, Distribution.UBUNTU).forEach { dist ->
                            FilterChip(
                                selected = selectedDist == dist,
                                onClick = {
                                    selectedDist = dist
                                    if (name.isBlank() || name == "Debian Linux" || name == "Ubuntu Linux") {
                                        name = "${dist.displayName} Linux"
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
