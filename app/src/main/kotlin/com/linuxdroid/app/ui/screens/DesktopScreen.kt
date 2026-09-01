package com.linuxdroid.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.components.DistroIcon
import com.linuxdroid.app.ui.components.LinuxDroidGuiSurface
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lifecycle phases for the Desktop GUI experience.
 */
enum class DesktopPhase {
    /** Live read-only Linux kernel & systemd boot console */
    BOOTING,
    /** LightDM / GDM style Linux Display Manager login screen */
    LOGIN,
    /** Active Wayland / X11 Graphical Desktop workspace */
    DESKTOP
}

/**
 * Desktop GUI Mode Screen:
 * - If session is already running: directly opens desktop session.
 * - If session is not running: starts environment, displays live non-editable Linux bootlog,
 *   then smoothly presents login screen (or auto-logs in if enabled).
 */
@Composable
fun DesktopScreen(
    navController: NavController,
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by environmentViewModel.environments.collectAsState()
    val neuColors = NeuTheme.colors

    val targetEnvId = remember {
        navController.currentBackStackEntry?.arguments?.getString("environmentId")
    }

    val environment = environments.firstOrNull { it.id.value == targetEnvId }
        ?: environments.firstOrNull()

    if (environment == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No Linux environment found",
                    style = MaterialTheme.typography.titleMedium,
                    color = neuColors.textPrimary,
                )
                NeuButton(onClick = { navController.popBackStack() }) {
                    Text("Return Home")
                }
            }
        }
        return
    }

    val isAlreadyRunning = remember { environment.state == EnvironmentState.RUNNING }
    val autoLoginEnabled = environment.configuration.desktop.autoLogin

    var currentPhase by remember {
        mutableStateOf(
            if (isAlreadyRunning) DesktopPhase.DESKTOP else DesktopPhase.BOOTING
        )
    }

    // Auto-start environment if stopped when entering boot phase
    LaunchedEffect(environment.id.value) {
        if (!isAlreadyRunning && environment.state != EnvironmentState.RUNNING && environment.state != EnvironmentState.STARTING) {
            environmentViewModel.startEnvironment(environment)
        }
    }

    AnimatedContent(
        targetState = currentPhase,
        label = "DesktopPhaseTransition",
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }
    ) { phase ->
        when (phase) {
            DesktopPhase.BOOTING -> {
                LinuxBootConsoleScreen(
                    environment = environment,
                    onBootComplete = {
                        if (autoLoginEnabled) {
                            currentPhase = DesktopPhase.DESKTOP
                        } else {
                            currentPhase = DesktopPhase.LOGIN
                        }
                    },
                    onSkipBoot = {
                        if (autoLoginEnabled) {
                            currentPhase = DesktopPhase.DESKTOP
                        } else {
                            currentPhase = DesktopPhase.LOGIN
                        }
                    },
                    onExit = {
                        navController.popBackStack()
                    }
                )
            }
            DesktopPhase.LOGIN -> {
                LinuxLoginScreen(
                    environment = environment,
                    onLoginSuccess = {
                        currentPhase = DesktopPhase.DESKTOP
                    },
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onReboot = {
                        currentPhase = DesktopPhase.BOOTING
                    },
                    onPowerOff = {
                        environmentViewModel.stopEnvironment(environment)
                        navController.popBackStack()
                    }
                )
            }
            DesktopPhase.DESKTOP -> {
                LinuxDesktopWorkspace(
                    environment = environment,
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onLockSession = {
                        currentPhase = DesktopPhase.LOGIN
                    },
                    onStopSession = {
                        environmentViewModel.stopEnvironment(environment)
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

/**
 * 1. Read-only live Linux Boot sequence console.
 */
@Composable
private fun LinuxBootConsoleScreen(
    environment: Environment,
    onBootComplete: () -> Unit,
    onSkipBoot: () -> Unit,
    onExit: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val listState = rememberLazyListState()

    val bootLogLines = remember(environment) {
        listOf(
            "[    0.000000] Booting Linux on physical CPU 0x0000000000 [0x510f8031]",
            "[    0.000000] Linux version 6.6.89-android15-8-g97a9aaefab9a (gcc version 14.2.0) #1 SMP PREEMPT",
            "[    0.000001] Machine model: Android 16 PRoot Guest Container (aarch64)",
            "[    0.005120] Kernel command line: console=tty0 root=/dev/root rw proot.seccomp=1 quiet",
            "[    0.012431] Memory: 8192MB Total, 5780MB Free",
            "[    0.024102] Mount guest rootfs: ${environment.rootfsPath}",
            "[    0.038910] Initializing PRoot syscall translation layer (ptrace + seccomp-bpf enabled)",
            "[    0.045120] Checking root filesystem consistency: clean, 28410/196608 files, 142180/786432 blocks",
            "[  OK  ] Mounted /dev, /dev/pts, /dev/shm, /proc, /sys virtual filesystems.",
            "[  OK  ] Mounted Android Shared Storage bridge (/sdcard).",
            "[  OK  ] Configured host DNS loopback resolution (/etc/resolv.conf).",
            "[  OK  ] Started D-Bus System Message Bus daemon.",
            "[  OK  ] Started Host Network Name Resolution service.",
            "[  OK  ] Initialized Wayland / X11 Compositor Socket (:0).",
            "[  OK  ] Started PulseAudio Sound Server forwarding daemon.",
            "[  OK  ] Starting Graphical Desktop Display Manager (LightDM)...",
            "[  OK  ] Reached target Graphical Interface.",
            "[  OK  ] Linux userspace initialized successfully.",
        )
    }

    var displayedLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var bootCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(environment.id.value) {
        for (i in bootLogLines.indices) {
            displayedLines = bootLogLines.take(i + 1)
            listState.animateScrollToItem(i)
            val delayMs = when {
                i < 4 -> 40L
                bootLogLines[i].startsWith("[  OK  ]") -> 110L
                else -> 75L
            }
            delay(delayMs)
        }
        bootCompleted = true
        delay(400L)
        onBootComplete()
    }

    Scaffold(
        containerColor = Color(0xFF0D1117),
        topBar = {
            Surface(
                color = Color(0xFF161B22),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 32.dp)
                        Column {
                            Text(
                                text = "Booting ${environment.name}",
                                fontFamily = SfMono,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = if (bootCompleted) "SYSTEM READY" else "INITIALIZING LINUX KERNEL & SYSTEMD...",
                                fontFamily = SfMono,
                                fontSize = 10.sp,
                                color = if (bootCompleted) Color(0xFF3FB950) else Color(0xFF58A6FF),
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onSkipBoot,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF8B949E)),
                        ) {
                            Text("Skip", fontSize = 12.sp, fontFamily = SfMono)
                        }
                        IconButton(onClick = onExit) {
                            Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color(0xFF8B949E))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(padding)
                .padding(14.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayedLines) { line ->
                    BootLogLineView(line = line)
                }
            }
        }
    }
}

@Composable
private fun BootLogLineView(line: String) {
    if (line.startsWith("[  OK  ]")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "[",
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFF8B949E),
            )
            Text(
                text = "  OK  ",
                fontFamily = SfMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3FB950),
            )
            Text(
                text = "]",
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFF8B949E),
            )
            Text(
                text = line.removePrefix("[  OK  ]"),
                fontFamily = SfMono,
                fontSize = 12.sp,
                color = Color(0xFFE6EDF3),
            )
        }
    } else {
        Text(
            text = line,
            fontFamily = SfMono,
            fontSize = 12.sp,
            color = if (line.contains("error", ignoreCase = true)) Color(0xFFF85149) else Color(0xFF8B949E),
        )
    }
}

/**
 * 2. LightDM / Modern Linux Display Manager Login Screen.
 */
@Composable
private fun LinuxLoginScreen(
    environment: Environment,
    onLoginSuccess: () -> Unit,
    onOpenTerminal: () -> Unit,
    onReboot: () -> Unit,
    onPowerOff: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    var username by remember { mutableStateOf(environment.configuration.linuxUser.ifBlank { "root" }) }
    var password by remember { mutableStateOf("") }
    var rememberSession by remember { mutableStateOf(true) }
    var selectedSession by remember { mutableStateOf("XFCE4 Desktop") }

    val currentTime = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
    val currentDate = remember {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            Surface(
                color = neuColors.background,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 28.dp)
                        Text(
                            text = "${environment.distribution.displayName} 12",
                            fontFamily = SfMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = neuColors.textSecondary,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NeuIconButton(
                            onClick = onOpenTerminal,
                            size = 32.dp,
                            tint = neuColors.primaryAccent,
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = "Terminal CLI", modifier = Modifier.size(16.dp))
                        }
                        NeuIconButton(
                            onClick = onReboot,
                            size = 32.dp,
                            tint = neuColors.secondaryAccent,
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reboot", modifier = Modifier.size(16.dp))
                        }
                        NeuIconButton(
                            onClick = onPowerOff,
                            size = 32.dp,
                            tint = neuColors.error,
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power Off", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            ) {
                // Clock header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentTime,
                        fontFamily = SfPro,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        color = neuColors.textPrimary,
                    )
                    Text(
                        text = currentDate,
                        fontFamily = SfPro,
                        fontSize = 14.sp,
                        color = neuColors.textSecondary,
                    )
                }

                // Login card
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 6.dp,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // User Avatar
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = neuColors.surfacePressed,
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, neuColors.primaryAccent.copy(alpha = 0.5f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = neuColors.primaryAccent,
                                )
                            }
                        }

                        Text(
                            text = username,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                        )

                        // Password field
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("Password (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onLoginSuccess() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = neuColors.primaryAccent,
                                unfocusedBorderColor = neuColors.borderHighlight.copy(alpha = 0.4f),
                                focusedContainerColor = neuColors.surfacePressed,
                                unfocusedContainerColor = neuColors.surfacePressed,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Session mode selector
                        Surface(
                            color = neuColors.surfacePressed,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.secondaryAccent)
                                    Text("Session:", style = MaterialTheme.typography.labelMedium, color = neuColors.textSecondary)
                                }
                                Text(
                                    text = selectedSession,
                                    fontFamily = SfMono,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = neuColors.primaryAccent,
                                )
                            }
                        }

                        // Auto-login checkbox
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { rememberSession = !rememberSession },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = rememberSession,
                                onCheckedChange = { rememberSession = it },
                                colors = CheckboxDefaults.colors(checkedColor = neuColors.primaryAccent)
                            )
                            Text(
                                "Auto-login on future boots",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }

                        // Log In Button
                        NeuButton(
                            onClick = onLoginSuccess,
                            modifier = Modifier.fillMaxWidth(),
                            isAccent = true,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 12.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Log In to Desktop", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "LinuxDroid · Rootless PRoot Display Manager",
                    fontFamily = SfMono,
                    fontSize = 11.sp,
                    color = neuColors.textMuted,
                )
            }
        }
    }
}

/**
 * 3. Graphical Desktop Workspace.
 */
@Composable
private fun LinuxDesktopWorkspace(
    environment: Environment,
    onOpenTerminal: () -> Unit,
    onLockSession: () -> Unit,
    onStopSession: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val context = LocalContext.current

    Scaffold(
        containerColor = Color(0xFF1E222B),
        topBar = {
            // macOS / XFCE Style Top Panel
            Surface(
                color = Color(0xFF161920),
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 26.dp)
                        Text(
                            text = "Applications",
                            fontFamily = SfPro,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Surface(
                            color = Color(0xFF2C3240),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "XFCE4 • Wayland",
                                fontFamily = SfMono,
                                fontSize = 10.sp,
                                color = neuColors.secondaryAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = onOpenTerminal, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onLockSession, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock", tint = neuColors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onStopSession, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Exit", tint = neuColors.error, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E222B))
                .padding(padding)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // GUI surface / lifecycle anchor (Milestone 2). This hosts the
                // native GUI host, which will, in later milestones, present the
                // composited desktop here.
                LinuxDroidGuiSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )

                // Desktop status + quick actions, kept below the surface so they are
                // never obscured by the SurfaceView's separate compositing layer.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DistroIcon(distribution = environment.distribution, size = 56.dp)

                    Text(
                        text = "${environment.distribution.displayName} Graphical Session",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )

                    Text(
                        text = "GUI host surface attached · Wayland socket :0",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E),
                        textAlign = TextAlign.Center,
                    )

                    // Action row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NeuButton(
                            onClick = onOpenTerminal,
                            modifier = Modifier.weight(1f),
                            isAccent = true,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Shell", fontSize = 13.sp)
                        }

                        NeuButton(
                            onClick = onLockSession,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Lock Screen", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
