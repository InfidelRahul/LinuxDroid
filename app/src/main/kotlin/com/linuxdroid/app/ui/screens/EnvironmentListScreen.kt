package com.linuxdroid.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.linuxdroid.app.ui.theme.LinuxDroidTheme
import com.linuxdroid.core.model.Distribution
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.EnvironmentState

/**
 * Displays the list of Linux environments.
 *
 * Button states are driven by actual EnvironmentState,
 * not by optimistic UI assumptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentListScreen(navController: NavController) {
    // TODO: inject ViewModel, observe StateFlow<List<Environment>>
    // Placeholder until ViewModel is wired
    val environments = emptyList<EnvironmentListItem>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Environments") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* TODO: create environment */ }) {
                Icon(Icons.Default.Add, contentDescription = "New Environment")
            }
        }
    ) { padding ->
        if (environments.isEmpty()) {
            EmptyEnvironmentsPlaceholder(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(environments) { item ->
                    EnvironmentCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun EmptyEnvironmentsPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "No environments yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Tap + to create a Linux environment",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentCard(item: EnvironmentListItem) {
    Card(
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
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${item.distribution.displayName} • ${item.architecture}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StateChip(state = item.state)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { /* TODO */ },
                    enabled = item.state.canStart(),
                ) {
                    Text("START")
                }
                OutlinedButton(
                    onClick = { /* TODO */ },
                    enabled = item.state.canStop(),
                ) {
                    Text("STOP")
                }
            }
        }
    }
}

@Composable
private fun StateChip(state: EnvironmentState) {
    val (containerColor, contentColor, label) = when (state) {
        EnvironmentState.RUNNING -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Running"
        )
        EnvironmentState.STARTING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Starting"
        )
        EnvironmentState.STOPPING -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Stopping"
        )
        EnvironmentState.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Failed"
        )
        EnvironmentState.READY, EnvironmentState.STOPPED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            state.name.lowercase().replaceFirstChar { it.uppercase() }
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            state.name.lowercase().replaceFirstChar { it.uppercase() }
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

/** Placeholder data class for the list — replace with domain model. */
data class EnvironmentListItem(
    val id: EnvironmentId,
    val name: String,
    val distribution: Distribution,
    val architecture: String,
    val state: EnvironmentState,
)

@Preview
@Composable
fun EnvironmentListScreenPreview() {
    LinuxDroidTheme {
        EnvironmentListScreen(navController = rememberNavController())
    }
}
