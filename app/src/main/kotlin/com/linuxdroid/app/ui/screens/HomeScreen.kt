package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.linuxdroid.app.R
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.SettingsViewModel
import com.linuxdroid.core.storage.StorageAuthorizationState

/**
 * Home screen — landing page with primary navigation actions and first-time storage setup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authState by settingsViewModel.authorizationState.collectAsState()
    val neuColors = NeuTheme.colors

    var showStorageDialog by remember {
        mutableStateOf(!settingsViewModel.hasPromptedStorageAccess() && authState !is StorageAuthorizationState.Authorized)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.checkStorageAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                ),
                actions = {
                    NeuIconButton(
                        onClick = { navController.navigate(Screen.Settings.route) },
                        size = 40.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Neumorphic App Hero Banner
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NeuIconButton(
                            onClick = {},
                            enabled = false,
                            size = 44.dp,
                            tint = neuColors.primaryAccent
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(24.dp))
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = neuColors.textPrimary,
                            )
                            Text(
                                text = "Rootless Linux on Android",
                                style = MaterialTheme.typography.bodyMedium,
                                color = neuColors.textSecondary,
                            )
                        }
                    }
                }
            }

            Text(
                "Quick Navigation",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = neuColors.textPrimary,
            )

            // Quick actions
            NeuQuickActionCard(
                icon = Icons.AutoMirrored.Filled.List,
                title = "Environments",
                description = "Manage Debian & Ubuntu Linux environments",
                onClick = { navController.navigate(Screen.Environments.route) },
            )

            NeuQuickActionCard(
                icon = Icons.Default.BugReport,
                title = "Diagnostics & Failure Reports",
                description = "Subsystem health checks and log exports",
                onClick = { navController.navigate(Screen.Diagnostics.route) },
            )

            NeuQuickActionCard(
                icon = Icons.Default.Info,
                title = "About",
                description = "Version, architecture, and engine specs",
                onClick = { navController.navigate(Screen.About.route) },
            )
        }

        if (showStorageDialog) {
            SharedStorageAccessDialog(
                onDismiss = {
                    settingsViewModel.setPromptedStorageAccess(true)
                    showStorageDialog = false
                },
                onGrant = {
                    settingsViewModel.setPromptedStorageAccess(true)
                    showStorageDialog = false
                    settingsViewModel.getPermissionIntent()?.let { intent ->
                        context.startActivity(intent)
                    }
                }
            )
        }
    }
}

@Composable
private fun SharedStorageAccessDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = neuColors.background,
        icon = {
            NeuIconButton(
                onClick = {},
                enabled = false,
                size = 52.dp,
                tint = neuColors.primaryAccent,
            ) {
                Icon(
                    Icons.Default.FolderShared,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = "Android Shared Storage",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = neuColors.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "LinuxDroid allows you to share files seamlessly between Android and your Linux environments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neuColors.textSecondary,
                )
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 2.dp,
                    isInset = true,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                            Text("Access Downloads & Documents in Linux", fontSize = 13.sp, color = neuColors.textPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                            Text("Mounted at /sdcard and /home/user/Android", fontSize = 13.sp, color = neuColors.textPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                            Text("Rootless, secure, and isolated execution", fontSize = 13.sp, color = neuColors.textPrimary)
                        }
                    }
                }
                Text(
                    text = "Grant storage access to enable full file sharing, or configure it later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.textMuted
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = onGrant,
                shape = RoundedCornerShape(12.dp),
                isAccent = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Grant Access", fontSize = 14.sp)
            }
        },
        dismissButton = {
            NeuButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Maybe Later", fontSize = 14.sp)
            }
        }
    )
}

@Composable
private fun NeuQuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NeuIconButton(
                onClick = onClick,
                size = 44.dp,
                tint = neuColors.primaryAccent,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = neuColors.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = neuColors.textSecondary,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = neuColors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Preview
@Composable
fun HomeScreenPreview() {
    LinuxDroidTheme {
        HomeScreen(navController = rememberNavController())
    }
}
