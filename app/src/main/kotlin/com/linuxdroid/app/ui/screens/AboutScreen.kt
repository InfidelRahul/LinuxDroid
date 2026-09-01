package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxdroid.app.BuildConfig
import com.linuxdroid.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val neuColors = NeuTheme.colors

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = { Text("About", color = neuColors.textPrimary) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // App Header Card
            NeuCard(modifier = Modifier.fillMaxWidth()) {
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
                            size = 46.dp,
                            tint = neuColors.primaryAccent
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(24.dp))
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
                        "LinuxDroid provides a persistent, rootless Linux userspace running directly on Android hardware.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = neuColors.textSecondary,
                    )
                }
            }

            // Specs Card
            Text(
                "System Specifications",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                color = neuColors.textPrimary,
            )
            NeuCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutItem("Architecture", "Rootless Linux userspace on Android")
                    AboutItem("Runtime", "proot (ptrace-based rootless chroot)")
                    AboutItem("Initial Distribution", "Debian & Ubuntu ARM64")
                    AboutItem("Display", "Wayland-first (XWayland for X11 compatibility)")
                    AboutItem("Root Required", "No")
                    AboutItem("VM Required", "No")
                    AboutItem("Custom Kernel Required", "No")
                }
            }
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = neuColors.textPrimary
        )
    }
}

@Preview
@Composable
fun AboutScreenPreview() {
    LinuxDroidTheme { AboutScreen() }
}
