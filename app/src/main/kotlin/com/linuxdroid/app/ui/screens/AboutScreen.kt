package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linuxdroid.app.BuildConfig
import com.linuxdroid.app.ui.theme.LinuxDroidTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("About") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LinuxDroid", style = MaterialTheme.typography.headlineMedium)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            AboutItem("Architecture", "Rootless Linux userspace on Android")
            AboutItem("Runtime", "proot (ptrace-based rootless chroot)")
            AboutItem("Initial Distribution", "Debian arm64")
            AboutItem("Display", "Wayland-first (XWayland for X11 compatibility)")
            AboutItem("Root Required", "No")
            AboutItem("VM Required", "No")
            AboutItem("Custom Kernel Required", "No")
            HorizontalDivider()
            Text(
                "LinuxDroid provides a persistent, rootless Linux userspace running directly on Android hardware.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview
@Composable
fun AboutScreenPreview() {
    LinuxDroidTheme { AboutScreen() }
}
