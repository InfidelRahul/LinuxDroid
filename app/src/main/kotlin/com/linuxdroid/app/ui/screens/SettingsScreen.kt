package com.linuxdroid.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.SettingsViewModel
import com.linuxdroid.core.model.AppThemeMode
import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.storage.StorageAuthorizationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authState by viewModel.authorizationState.collectAsState()
    val currentThemeMode by viewModel.themeMode.collectAsState()
    val neuColors = NeuTheme.colors

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkStorageAccess()
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
                title = { Text("Settings", color = neuColors.textPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                )
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Theme Mode Section
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = neuColors.textPrimary,
            )
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NeuIconButton(
                            onClick = {},
                            enabled = false,
                            size = 38.dp,
                            tint = neuColors.primaryAccent
                        ) {
                            Icon(
                                when (currentThemeMode) {
                                    AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                    AppThemeMode.LIGHT -> Icons.Default.LightMode
                                    AppThemeMode.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Theme Mode",
                                style = MaterialTheme.typography.titleSmall,
                                color = neuColors.textPrimary
                            )
                            Text(
                                "Neumorphic Light and Dark UI styling",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }
                    }

                    NeuSegmentedControl(
                        items = listOf(AppThemeMode.SYSTEM, AppThemeMode.LIGHT, AppThemeMode.DARK),
                        selectedItem = currentThemeMode,
                        onItemSelected = { mode -> viewModel.setThemeMode(mode) },
                        itemLabel = { mode ->
                            when (mode) {
                                AppThemeMode.SYSTEM -> "System"
                                AppThemeMode.LIGHT -> "Light"
                                AppThemeMode.DARK -> "Dark"
                            }
                        },
                        itemIcon = { mode ->
                            Icon(
                                when (mode) {
                                    AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                    AppThemeMode.LIGHT -> Icons.Default.LightMode
                                    AppThemeMode.DARK -> Icons.Default.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            // Storage Section
            Text(
                "Shared Storage",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = neuColors.textPrimary,
            )
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NeuIconButton(
                            onClick = {},
                            enabled = false,
                            size = 38.dp,
                            tint = neuColors.primaryAccent
                        ) {
                            Icon(
                                Icons.Default.FolderShared,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Android Shared Directory",
                                style = MaterialTheme.typography.titleSmall,
                                color = neuColors.textPrimary
                            )
                            Text(
                                viewModel.sharedDirPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary,
                            )
                        }
                    }

                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.4f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val (statusText, statusColor) = when (authState) {
                            is StorageAuthorizationState.Authorized -> "Authorized" to neuColors.success
                            is StorageAuthorizationState.Unauthorized -> "Not Authorized" to neuColors.warning
                            is StorageAuthorizationState.Error -> "Error" to neuColors.error
                            StorageAuthorizationState.Unknown -> "Checking…" to neuColors.textMuted
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = if (authState is StorageAuthorizationState.Authorized) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (authState !is StorageAuthorizationState.Authorized) {
                                NeuButton(
                                    onClick = {
                                        viewModel.getPermissionIntent()?.let { intent ->
                                            context.startActivity(intent)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    isAccent = true,
                                ) {
                                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Grant", fontSize = 13.sp)
                                }
                            }

                            NeuButton(
                                onClick = { viewModel.checkStorageAccess() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Verify", fontSize = 13.sp)
                            }
                        }
                    }

                    if (authState is StorageAuthorizationState.Unauthorized) {
                        Text(
                            text = (authState as StorageAuthorizationState.Unauthorized).reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = neuColors.error,
                        )
                    } else if (authState is StorageAuthorizationState.Error) {
                        Text(
                            text = (authState as StorageAuthorizationState.Error).message,
                            style = MaterialTheme.typography.bodySmall,
                            color = neuColors.error,
                        )
                    }
                }
            }

            // Device / System Info Section
            Text(
                "System & Hardware",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = neuColors.textPrimary,
            )
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingInfoRow("Detected ABI", Architecture.current().abiName)
                    SettingInfoRow("Supported ABIs", android.os.Build.SUPPORTED_ABIS.joinToString(", "))
                    SettingInfoRow("Device Model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    SettingInfoRow("Android API", "${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
                    SettingInfoRow("Runtime Engine", "PRoot (Rootless syscall translation via ptrace)")
                }
            }
        }
    }
}

@Composable
private fun SettingInfoRow(title: String, value: String) {
    val neuColors = NeuTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = neuColors.textPrimary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = neuColors.textSecondary,
        )
    }
}
