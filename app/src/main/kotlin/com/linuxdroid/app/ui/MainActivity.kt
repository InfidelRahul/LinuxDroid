package com.linuxdroid.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.linuxdroid.app.ui.navigation.LinuxDroidNavGraph
import com.linuxdroid.app.ui.theme.LinuxDroidTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single Activity for LinuxDroid.
 *
 * This activity hosts the Compose navigation graph.
 * It does NOT directly manipulate Linux processes.
 * All Linux operations go through the ViewModel → Manager → RuntimeBackend chain.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinuxDroidTheme {
                LinuxDroidNavGraph()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainPreview() {
    LinuxDroidTheme {
        LinuxDroidNavGraph()
    }
}
