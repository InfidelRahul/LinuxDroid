package com.linuxdroid.app.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment as AndroidEnvironment
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linuxdroid.app.BuildConfig
import com.linuxdroid.app.ui.theme.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.navigation.NavController
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.model.EnvironmentState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController? = null,
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val neuColors = NeuTheme.colors
    val context = LocalContext.current
    val environments by environmentViewModel.environments.collectAsState()

    val firstInstalledDistro = environments.firstOrNull {
        it.state == EnvironmentState.READY || it.state == EnvironmentState.RUNNING || it.state == EnvironmentState.STOPPED
    }?.let { "${it.distribution.displayName} (${it.architecture.abiName})" }
        ?: environments.firstOrNull()?.let { "${it.distribution.displayName} (${it.architecture.abiName})" }
        ?: "None installed"

    val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} ${Build.MODEL}"
    val processorInfo = getProcessorInfo()
    val memoryInfo = getMemoryInfo(context)
    val storageInfo = getStorageInfo(context)
    val batteryInfo = getBatteryStatus(context)
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val kernelRelease = System.getProperty("os.version") ?: "Linux Kernel"
    val rootStatus = getRootStatus()
    val archText = "ARM64 (${Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"}) Linux userspace"

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = { Text("About", color = neuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                ),
                navigationIcon = {
                    if (navController?.previousBackStackEntry != null) {
                        NeuIconButton(
                            onClick = { navController.popBackStack() },
                            size = 38.dp,
                            tint = neuColors.textPrimary,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // App Header Card (macOS System Profile Style)
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column {
                    MacosWindowHeader(
                        title = "About LinuxDroid",
                        badgeText = "v${BuildConfig.VERSION_NAME}",
                        subtitle = "ARM64"
                    )

                    HorizontalDivider(
                        color = neuColors.borderHighlight.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )

                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            NeuIconButton(
                                onClick = {},
                                enabled = false,
                                size = 48.dp,
                                tint = neuColors.primaryAccent
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(26.dp))
                            }
                            Column {
                                Text(
                                    "LinuxDroid",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = neuColors.textPrimary
                                )
                                Text(
                                    "Version ${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = neuColors.textSecondary
                                )
                            }
                        }
                        Text(
                            "LinuxDroid provides a persistent Linux userspace running directly on Android hardware.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neuColors.textSecondary,
                        )
                    }
                }
            }

            // System Specifications
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                Text(
                    "System Specifications",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    color = neuColors.textPrimary,
                )
            }
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutItem("Architecture", archText)
                    AboutItem("Runtime", "proot (Build for LinuxDroid)")
                    AboutItem("Distribution", firstInstalledDistro)
                    AboutItem("Display", "Wayland (Default)")
                    AboutItem("Root Status", rootStatus)
                }
            }

            // Device & Hardware Information (Android About Section style)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                Text(
                    "Device & Hardware Specifications",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    color = neuColors.textPrimary,
                )
            }
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutItem("Device Model", deviceModel)
                    AboutItem("Processor / SoC", processorInfo)
                    AboutItem("RAM Memory", memoryInfo)
                    AboutItem("Internal Storage", storageInfo)
                    AboutItem("Battery", batteryInfo)
                    AboutItem("Android OS", androidVersion)
                    AboutItem("Linux Kernel", kernelRelease)
                }
            }

            // Developer & Open Source
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(20.dp))
                Text(
                    "Developer & Open Source",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    color = neuColors.textPrimary,
                )
            }
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutLinkItem(
                        label = "Developer",
                        value = "InfidelRahul",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/InfidelRahul"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)
                    AboutLinkItem(
                        label = "GitHub Repository",
                        value = "InfidelRahul/LinuxDroid",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/InfidelRahul/LinuxDroid"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)
                    AboutLinkItem(
                        label = "Issue Tracker",
                        value = "Report Bug / Feature",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/InfidelRahul/LinuxDroid/issues"))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutLinkItem(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = neuColors.textSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = neuColors.primaryAccent
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = neuColors.primaryAccent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun AboutItem(label: String, value: String) {
    val neuColors = NeuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = neuColors.textSecondary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = neuColors.textPrimary
        )
    }
}

private fun getProcessorInfo(): String {
    val cores = Runtime.getRuntime().availableProcessors()
    val hardware = if (Build.HARDWARE.isNotBlank() && Build.HARDWARE != "unknown") Build.HARDWARE else Build.BOARD
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    return "$hardware • $cores Cores ($primaryAbi)"
}

private fun getMemoryInfo(context: Context): String {
    return try {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val usedMem = memInfo.totalMem - memInfo.availMem
            val totalFormatted = Formatter.formatFileSize(context, memInfo.totalMem)
            val usedFormatted = Formatter.formatFileSize(context, usedMem)
            "$usedFormatted used / $totalFormatted total"
        } else {
            "Available"
        }
    } catch (e: Exception) {
        "Available"
    }
}

private fun getStorageInfo(context: Context): String {
    return try {
        val stat = StatFs(AndroidEnvironment.getDataDirectory().path)
        val available = stat.availableBytes
        val total = stat.totalBytes
        val availFormatted = Formatter.formatFileSize(context, available)
        val totalFormatted = Formatter.formatFileSize(context, total)
        "$availFormatted free / $totalFormatted total"
    } catch (e: Exception) {
        "Available"
    }
}

private fun getBatteryStatus(context: Context): String {
    return try {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (pct >= 0) {
            "$pct% (${if (isCharging) "Charging" else "Discharging"})"
        } else {
            "Available"
        }
    } catch (e: Exception) {
        "Available"
    }
}

private fun getRootStatus(): String {
    val suPaths = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su"
    )
    val hasSu = suPaths.any { File(it).exists() }
    return if (hasSu) "Rooted (Superuser present)" else "Non-Root (Rootless sandbox)"
}

