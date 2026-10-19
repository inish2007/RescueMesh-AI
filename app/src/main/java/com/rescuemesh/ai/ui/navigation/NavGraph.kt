package com.rescuemesh.ai.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rescuemesh.ai.ui.screens.*

sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Observations : Screen("observations", "Observe", Icons.Filled.Visibility, Icons.Outlined.Visibility)
    data object Map : Screen("map", "Map", Icons.Filled.Map, Icons.Outlined.Map)
    data object Peers : Screen("peers", "Peers", Icons.Filled.WifiTethering, Icons.Outlined.WifiTethering)
    data object Metrics : Screen("metrics", "Metrics", Icons.Filled.BarChart, Icons.Outlined.BarChart)
}

val bottomNavScreens = listOf(
    Screen.Home,
    Screen.Observations,
    Screen.Map,
    Screen.Peers,
    Screen.Metrics
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueMeshNavHost(
    navController: NavHostController = rememberNavController(),
    homeScreen: @Composable () -> Unit,
    observationsScreen: @Composable () -> Unit,
    mapScreen: @Composable () -> Unit,
    peersScreen: @Composable () -> Unit,
    metricsScreen: @Composable () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                bottomNavScreens.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.label
                            )
                        },
                        label = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) { homeScreen() }
            composable(Screen.Observations.route) { observationsScreen() }
            composable(Screen.Map.route) { mapScreen() }
            composable(Screen.Peers.route) { peersScreen() }
            composable(Screen.Metrics.route) { metricsScreen() }
        }
    }
}
