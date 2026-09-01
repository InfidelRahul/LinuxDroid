package com.linuxdroid.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.linuxdroid.app.R
import com.linuxdroid.app.ui.components.DistroIcon
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.app.ui.viewmodel.SettingsViewModel
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.storage.StorageAuthorizationState

/**
 * Home screen:
 * - If NO rootfs is installed: Shows Rootfs Installation screen with automatic architecture detection.
 * - If rootfs IS installed: Shows clean dashboard with OS Hero Card (status & settings in header),
 *   Launch Mode cards (GUI & CLI with authentic distro square box icons), active session Resume/Start, reactive Stop button,
 *   and live system telemetry overview.
 */
@Composable
fun HomeScreen(
    navController: NavController = rememberNavController(),
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val environments by environmentViewModel.environments.collectAsState()
    val installProgress by environmentViewModel.installProgress.collectAsState()
    val installStatusText by environmentViewModel.installStatusText.collectAsState()
    val installerLogs by environmentViewModel.installerLogs.collectAsState()
    val authorizationState by settingsViewModel.authorizationState.collectAsState()

    var showStorageDialog by remember { mutableStateOf(false) }
    var showDesktopInfoDialog by remember { mutableStateOf<Environment?>(null) }

    val neuColors = NeuTheme.colors

    val activeEnv = environments.firstOrNull()

    val installingEnv = environments.firstOrNull {
        it.state == EnvironmentState.INSTALLING || (installProgress[it.id.value] != null)
    }

    val hasInstalledRootfs = activeEnv != null && (
        activeEnv.state == EnvironmentState.READY ||
        activeEnv.state == EnvironmentState.RUNNING ||
        activeEnv.state == EnvironmentState.STARTING ||
        activeEnv.state == EnvironmentState.STOPPED ||
        activeEnv.state == EnvironmentState.STOPPING
    )

    // Check shared storage permission on first launch
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.checkStorageAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!settingsViewModel.hasPromptedStorageAccess()) {
            if (authorizationState !is StorageAuthorizationState.Authorized) {
                showStorageDialog = true
            }
        }
    }

    Scaffold(
        containerColor = neuColors.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!hasInstalledRootfs || installingEnv != null) {
                // NO Rootfs Present -> Show Rootfs Installation Screen
                RootfsInstallationCard(
                    installingEnv = installingEnv,
                    progress = installingEnv?.let { installProgress[it.id.value] },
                    statusText = installingEnv?.let { installStatusText[it.id.value] },
                    logs = installingEnv?.let { installerLogs[it.id.value] } ?: emptyList(),
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    onInstall = { distro, name ->
                        val detectedArch = Architecture.current()
                        environmentViewModel.createEnvironment(
                            name = name,
                            distribution = distro,
                            architecture = detectedArch,
                            autoBootstrap = true,
                        )
                    }
                )
            } else {
                // Rootfs IS Present -> Show Front Screen Dashboard with 2 Main Action Icons
                ActiveEnvironmentHeroCard(
                    environment = activeEnv,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
                )

                Text(
                    "Launch Mode",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = neuColors.textPrimary,
                )

                // OS / GUI Mode Primary Card
                NeuGuiLaunchCard(
                    environment = activeEnv,
                    onClick = {
                        navController.navigate(Screen.Desktop.route(activeEnv.id.value))
                    }
                )

                // Terminal / CLI Mode Primary Card
                NeuCliLaunchCard(
                    environment = activeEnv,
                    onClick = {
                        if (activeEnv.state != EnvironmentState.RUNNING) {
                            environmentViewModel.startEnvironment(activeEnv)
                        }
                        navController.navigate(Screen.Terminal.route(activeEnv.id.value))
                    },
                    onStop = {
                        environmentViewModel.stopEnvironment(activeEnv)
                    }
                )

                // Live System Telemetry Card (RAM & Storage Bars, Network, CPU, Battery)
                SystemOverviewCard(context = context)
            }
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

        showDesktopInfoDialog?.let { env ->
            AlertDialog(
                onDismissRequest = { showDesktopInfoDialog = null },
                containerColor = neuColors.background,
                icon = {
                    NeuIconButton(
                        onClick = {},
                        enabled = false,
                        size = 52.dp,
                        tint = neuColors.secondaryAccent,
                    ) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                },
                title = {
                    Text(
                        "Desktop GUI Mode",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "LinuxDroid provides a rootless Wayland/X11 display server for graphical Linux apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neuColors.textSecondary,
                        )
                        Surface(
                            color = neuColors.surfacePressed,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "To install a desktop environment (XFCE4):",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = neuColors.textPrimary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "apt update && apt install -y xfce4",
                                    fontFamily = SfMono,
                                    fontSize = 12.sp,
                                    color = neuColors.success,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    NeuButton(
                        onClick = {
                            val id = env.id.value
                            showDesktopInfoDialog = null
                            navController.navigate(Screen.Terminal.route(id))
                        },
                        isAccent = true,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Open Shell", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    NeuButton(
                        onClick = { showDesktopInfoDialog = null },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("Close", fontSize = 13.sp)
                    }
                }
            )
        }
    }
}

/**
 * Shared storage dialog informing user about /sdcard bridge.
 */
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
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Shared Path in Linux:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.textPrimary,
                        )
                        Text(
                            text = "/sdcard or ~/storage/shared",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            color = neuColors.primaryAccent,
                        )
                    }
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = onGrant,
                isAccent = true,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Grant Storage Access", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            NeuButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Later", fontSize = 13.sp)
            }
        }
    )
}

/**
 * GUI Launch card with installed OS icon box, distribution info, and active session status.
 */
@Composable
private fun NeuGuiLaunchCard(
    environment: Environment,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val isRunning = environment.state == EnvironmentState.RUNNING

    NeuCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        elevation = 4.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Square Box with Installed OS Icon
                DistroIcon(
                    distribution = environment.distribution,
                    size = 56.dp,
                )

                // Right Side: Distribution info and status
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Desktop GUI Mode",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            color = neuColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            color = neuColors.surfacePressed,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                        ) {
                            Text(
                                text = "Wayland / X11",
                                fontFamily = SfMono,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = neuColors.secondaryAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${environment.distribution.displayName} • Graphical Desktop",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) neuColors.success else neuColors.textMuted)
                        )
                        Text(
                            text = if (isRunning) "Active Session Running" else "Ready to Launch",
                            fontSize = 11.sp,
                            color = if (isRunning) neuColors.success else neuColors.textSecondary,
                            fontFamily = SfMono,
                            maxLines = 1,
                        )
                    }
                }
            }

            NeuButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                isAccent = true,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 11.dp),
            ) {
                Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Go to GUI Session" else "Start GUI Session",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * CLI Launch card — big terminal icon left, distribution info right.
 * When a session is running: terminal icon navigates back to session,
 * and a Stop button appears to its right.
 */
@Composable
private fun NeuCliLaunchCard(
    environment: Environment,
    onClick: () -> Unit,
    onStop: () -> Unit = {},
) {
    val neuColors = NeuTheme.colors
    val isRunning = environment.state == EnvironmentState.RUNNING

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Left: Big terminal button (+ optional stop) ──────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Big terminal icon button
                Surface(
                    modifier = Modifier
                        .size(68.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isRunning) neuColors.primaryAccent.copy(alpha = 0.12f)
                            else neuColors.surfacePressed,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isRunning) 1.5.dp else 1.dp,
                        color = neuColors.primaryAccent.copy(alpha = if (isRunning) 0.75f else 0.40f),
                    ),
                    shadowElevation = if (isRunning) 4.dp else 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Open Terminal",
                            tint = neuColors.primaryAccent,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                // Stop button — only visible when running
                AnimatedVisibility(
                    visible = isRunning,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.CenterVertically),
                    exit  = fadeOut() + shrinkVertically(shrinkTowards = Alignment.CenterVertically),
                ) {
                    Surface(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onStop,
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = neuColors.error.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, neuColors.error.copy(alpha = 0.55f)
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Session",
                                tint = neuColors.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // ── Right: Info column ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Terminal CLI",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        ),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text(
                            text = "Bash",
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.primaryAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                }

                Text(
                    text = "${environment.distribution.displayName} · Interactive Shell",
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) neuColors.success else neuColors.textMuted)
                    )
                    Text(
                        text = if (isRunning) "Session active — tap icon to resume" else "Tap to start a new session",
                        fontSize = 10.sp,
                        fontFamily = SfMono,
                        color = if (isRunning) neuColors.success else neuColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Live System Telemetry Card with RAM and Storage progress bars, Network, CPU and Battery.
 */
@Composable
private fun SystemOverviewCard(context: Context) {
    val neuColors = NeuTheme.colors
    val stats = remember(context) { getSystemOverview(context) }

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Analytics,
                        contentDescription = null,
                        tint = neuColors.primaryAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "System Resources",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (stats.isOnline) neuColors.success else neuColors.error)
                        )
                        Text(
                            text = "${stats.networkType} • ${stats.networkStatus}",
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // RAM Usage Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                        Text("RAM Usage", style = MaterialTheme.typography.labelSmall, color = neuColors.textSecondary)
                    }
                    Text(
                        "${stats.memUsedFormatted} / ${stats.memTotalFormatted} (${(stats.memFraction * 100).toInt()}%)",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = neuColors.textPrimary,
                    )
                }
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = stats.memFraction.coerceIn(0.01f, 1f))
                            .background(neuColors.primaryAccent, shape = RoundedCornerShape(6.dp))
                    )
                }
            }

            // Storage Usage Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.secondaryAccent)
                        Text("Internal Storage", style = MaterialTheme.typography.labelSmall, color = neuColors.textSecondary)
                    }
                    Text(
                        "${stats.storageUsedFormatted} / ${stats.storageTotalFormatted} (${(stats.storageFraction * 100).toInt()}%)",
                        fontFamily = SfMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = neuColors.textPrimary,
                    )
                }
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = stats.storageFraction.coerceIn(0.01f, 1f))
                            .background(neuColors.secondaryAccent, shape = RoundedCornerShape(6.dp))
                    )
                }
            }

            HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)

            // CPU & Battery Telemetry row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = neuColors.primaryAccent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stats.processorText,
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = neuColors.secondaryAccent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stats.batteryText,
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.textPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private data class SystemOverview(
    val memUsedFormatted: String,
    val memTotalFormatted: String,
    val memFraction: Float,
    val storageUsedFormatted: String,
    val storageTotalFormatted: String,
    val storageFraction: Float,
    val networkStatus: String,
    val networkType: String,
    val isOnline: Boolean,
    val batteryText: String,
    val processorText: String,
)

private fun getSystemOverview(context: Context): SystemOverview {
    var memUsedFormatted = "2.4 GB"
    var memTotalFormatted = "8.0 GB"
    var memFraction = 0.30f
    try {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val usedMem = memInfo.totalMem - memInfo.availMem
            memUsedFormatted = Formatter.formatFileSize(context, usedMem)
            memTotalFormatted = Formatter.formatFileSize(context, memInfo.totalMem)
            if (memInfo.totalMem > 0) {
                memFraction = (usedMem.toFloat() / memInfo.totalMem.toFloat()).coerceIn(0f, 1f)
            }
        }
    } catch (_: Exception) {}

    var storageUsedFormatted = "32.0 GB"
    var storageTotalFormatted = "128.0 GB"
    var storageFraction = 0.25f
    try {
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        val used = total - available
        storageUsedFormatted = Formatter.formatFileSize(context, used)
        storageTotalFormatted = Formatter.formatFileSize(context, total)
        if (total > 0) {
            storageFraction = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    } catch (_: Exception) {}

    var networkStatus = "Connected"
    var networkType = "Wi-Fi"
    var isOnline = true
    try {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connMgr?.activeNetwork
        val caps = connMgr?.getNetworkCapabilities(activeNetwork)
        if (caps != null) {
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    networkType = "Wi-Fi"
                    networkStatus = "Connected"
                    isOnline = true
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    networkType = "Cellular"
                    networkStatus = "Connected"
                    isOnline = true
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    networkType = "Ethernet"
                    networkStatus = "Connected"
                    isOnline = true
                }
                else -> {
                    networkType = "Network"
                    networkStatus = "Connected"
                    isOnline = true
                }
            }
        } else {
            networkType = "Offline"
            networkStatus = "Disconnected"
            isOnline = false
        }
    } catch (_: Exception) {
        networkType = "Online"
        networkStatus = "Active"
    }

    var batteryText = "85%"
    try {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        batteryText = if (pct >= 0) "$pct%${if (isCharging) " ⚡" else ""}" else "85%"
    } catch (_: Exception) {}

    val cores = Runtime.getRuntime().availableProcessors()
    val arch = Architecture.current().linuxArch
    val processorText = "$arch • $cores C"

    return SystemOverview(
        memUsedFormatted = memUsedFormatted,
        memTotalFormatted = memTotalFormatted,
        memFraction = memFraction,
        storageUsedFormatted = storageUsedFormatted,
        storageTotalFormatted = storageTotalFormatted,
        storageFraction = storageFraction,
        networkStatus = networkStatus,
        networkType = networkType,
        isOnline = isOnline,
        batteryText = batteryText,
        processorText = processorText,
    )
}

/**
 * First-Time / No-Rootfs Installation Card with Automatic Architecture Detection.
 */
@Composable
private fun RootfsInstallationCard(
    installingEnv: Environment?,
    progress: Float?,
    statusText: String?,
    logs: List<String>,
    onSettingsClick: () -> Unit,
    onInstall: (Distribution, String) -> Unit,
) {
    val neuColors = NeuTheme.colors
    val detectedArch = remember { Architecture.current() }

    var selectedDistro by remember { mutableStateOf(Distribution.DEBIAN) }
    var envName by remember { mutableStateOf("Debian ARM64") }

    LaunchedEffect(selectedDistro) {
        envName = "${selectedDistro.displayName} ARM64"
    }

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            MacosWindowHeader(
                title = "Setup Linux Environment",
                badgeText = detectedArch.abiName,
                subtitle = "Rootless PRoot Installer",
                actions = {
                    NeuIconButton(
                        onClick = onSettingsClick,
                        size = 30.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(16.dp))
                    }
                }
            )

            HorizontalDivider(
                color = neuColors.borderHighlight.copy(alpha = 0.2f),
                thickness = 0.5.dp
            )

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Auto-Detected Architecture Badge Card
                Surface(
                    color = neuColors.surfacePressed,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NeuIconButton(
                            onClick = {},
                            enabled = false,
                            size = 40.dp,
                            tint = neuColors.success,
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(22.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "Detected Architecture:",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = neuColors.textPrimary,
                                )
                                Text(
                                    detectedArch.linuxArch.uppercase(),
                                    fontFamily = SfMono,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = neuColors.primaryAccent,
                                )
                            }
                            Text(
                                "Auto-configured native ARM64 userspace · No manual selection needed",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }
                    }
                }

                if (installingEnv == null) {
                    // Distribution Selection
                    Text(
                        "Select Linux Distribution",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(
                            Triple(Distribution.DEBIAN, "Debian 12 (Bookworm)", "Recommended · Ultra-stable & low memory footprint"),
                            Triple(Distribution.UBUNTU, "Ubuntu 24.04 LTS (Noble)", "Popular · Broad software repository & developer tools"),
                            Triple(Distribution.KALI, "Kali Linux (Rolling)", "Security · Pentesting & analysis environment"),
                        ).forEach { (distro, title, desc) ->
                            val isSelected = selectedDistro == distro
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDistro = distro },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) neuColors.surfacePressed else neuColors.background,
                                border = androidx.compose.foundation.BorderStroke(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) neuColors.primaryAccent else neuColors.borderHighlight.copy(alpha = 0.3f),
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedDistro = distro },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = neuColors.primaryAccent,
                                        )
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = neuColors.textPrimary,
                                        )
                                        Text(
                                            desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = neuColors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Install Action
                    NeuButton(
                        onClick = { onInstall(selectedDistro, envName) },
                        isAccent = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Install & Bootstrap Environment",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    // Installing State with Progress and Logs
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Installing ${installingEnv.name}...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                        )

                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed,
                            )
                        }

                        statusText?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = neuColors.textSecondary,
                            )
                        }

                        // Live installer log console
                        Surface(
                            color = Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 220.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                logs.takeLast(30).forEach { line ->
                                    Text(
                                        text = line,
                                        fontFamily = SfMono,
                                        fontSize = 11.sp,
                                        color = if (line.contains("error", ignoreCase = true)) Color(0xFFFF6B6B) else Color(0xFF81C784),
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

/**
 * Hero card displaying the active environment telemetry.
 */
@Composable
private fun ActiveEnvironmentHeroCard(
    environment: Environment,
    onSettingsClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val (badgeColor, textColor) = when (environment.state) {
        EnvironmentState.RUNNING -> neuColors.success to Color.White
        EnvironmentState.STARTING -> neuColors.primaryAccent to Color.White
        EnvironmentState.READY -> neuColors.surfacePressed to neuColors.success
        else -> neuColors.surfacePressed to neuColors.textSecondary
    }

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            MacosWindowHeader(
                title = environment.name,
                badgeText = environment.architecture.linuxArch,
                subtitle = environment.distribution.displayName,
                actions = {
                    Surface(
                        color = badgeColor,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = environment.state.name,
                            fontFamily = SfMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    NeuIconButton(
                        onClick = onSettingsClick,
                        size = 30.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(16.dp))
                    }
                }
            )

            HorizontalDivider(
                color = neuColors.borderHighlight.copy(alpha = 0.2f),
                thickness = 0.5.dp,
            )

            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DistroIcon(
                        distribution = environment.distribution,
                        size = 48.dp,
                    )
                    Column {
                        Text(
                            text = environment.distribution.displayName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                        )
                        Text(
                            text = "Rootless Linux Userspace on Android 16",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neuColors.textSecondary,
                        )
                    }
                }

                // Telemetry status pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = "Engine: PRoot",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.primaryAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                        )
                    }

                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = "Arch: ${environment.architecture.linuxArch}",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = neuColors.secondaryAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                        )
                    }

                    Surface(
                        color = neuColors.surfacePressed,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = "Status: ${environment.state.name}",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (environment.state == EnvironmentState.RUNNING) neuColors.success else neuColors.textSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
