package com.linuxdroid.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.linuxdroid.app.ui.screens.AboutScreen
import com.linuxdroid.app.ui.screens.DiagnosticsScreen
import com.linuxdroid.app.ui.screens.EnvironmentListScreen
import com.linuxdroid.app.ui.screens.HomeScreen
import com.linuxdroid.app.ui.screens.SettingsScreen

/**
 * Top-level navigation destinations.
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Environments : Screen("environments")
    object EnvironmentDetail : Screen("environment/{environmentId}") {
        fun route(environmentId: String) = "environment/$environmentId"
    }
    object Settings : Screen("settings")
    object Diagnostics : Screen("diagnostics")
    object About : Screen("about")
}

/**
 * Top-level navigation graph for LinuxDroid.
 */
@Composable
fun LinuxDroidNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Environments.route) {
            EnvironmentListScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen()
        }
        composable(Screen.About.route) {
            AboutScreen()
        }
    }
}
