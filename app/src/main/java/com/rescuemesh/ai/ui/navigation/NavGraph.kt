package com.rescuemesh.ai.ui.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rescuemesh.ai.ui.screens.*

/**
 * Screen navigation routes for RescueMesh AI.
 *
 * Implements Gate 3 5-Tab Navigation Standard:
 * 1. [Home] (route: "home")
 * 2. [LiveMap] (route: "live_map", with "map" as alias)
 * 3. [OfflineMaps] (route: "offline_maps")
 * 4. [Reports] (route: "reports", with "observations" as alias)
 * 5. [Chat] (route: "chat")
 *
 * Secondary Destinations:
 * - [Settings] (route: "settings")
 * - [Peers] (route: "peers")
 * - [Metrics] (route: "metrics")
 * - [ObservationDetail] (route: "observation_detail/{eventId}")
 */
sealed class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object LiveMap : Screen("live_map", "Live Map", Icons.Filled.Map, Icons.Outlined.Map)
    data object OfflineMaps : Screen("offline_maps", "Offline Maps", Icons.Filled.CloudDownload, Icons.Outlined.CloudDownload)
    data object Reports : Screen("reports", "Reports", Icons.Filled.Assignment, Icons.Outlined.Assignment)
    data object Chat : Screen("chat", "Chat", Icons.Filled.Forum, Icons.Outlined.Forum)

    // Secondary destinations
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)

    // Backwards-compatible aliases for Gate 1 & 2 callers
    data object Observations : Screen("observations", "Reports", Icons.Filled.Assignment, Icons.Outlined.Assignment)
    data object Map : Screen("map", "Live Map", Icons.Filled.Map, Icons.Outlined.Map)
    data object Peers : Screen("peers", "Peers", Icons.Filled.WifiTethering, Icons.Outlined.WifiTethering)
    data object Metrics : Screen("metrics", "Metrics", Icons.Filled.BarChart, Icons.Outlined.BarChart)

    companion object {
        const val OBSERVATION_DETAIL_ROUTE = "observation_detail/{eventId}"
        fun observationDetail(eventId: String) = "observation_detail/$eventId"

        const val CHAT_WITH_EVENT_ROUTE = "chat?eventId={eventId}"
        fun chatWithEvent(eventId: String) = "chat?eventId=$eventId"

        val primaryTabs: List<Screen> by lazy { bottomNavScreens }
    }
}

/**
 * Gate 3 Authoritative 5 Bottom Navigation Destinations.
 */
val bottomNavScreens = listOf(
    Screen.Home,
    Screen.LiveMap,
    Screen.OfflineMaps,
    Screen.Reports,
    Screen.Chat
)

/**
 * CompositionLocal providing active NavHostController to nested screens.
 */
val LocalNavController = staticCompositionLocalOf<NavHostController?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueMeshNavHost(
    navController: NavHostController = rememberNavController(),
    homeScreen: @Composable (
        onNavigateToObservation: (String) -> Unit,
        onNavigateToChat: (String?) -> Unit,
        onNavigateToLiveMap: () -> Unit,
        onNavigateToReportCreate: () -> Unit
    ) -> Unit,
    liveMapScreen: @Composable (
        onNavigateToObservation: (String) -> Unit,
        onNavigateToChat: ((String) -> Unit)?
    ) -> Unit,
    offlineMapsScreen: @Composable (onNavigateToSituationalCanvas: () -> Unit) -> Unit,
    reportsScreen: @Composable (onNavigateToObservation: (String) -> Unit) -> Unit,
    chatScreen: @Composable (linkedEventId: String?, onNavigateToMap: ((String) -> Unit)?) -> Unit,
    peersScreen: @Composable () -> Unit = {},
    metricsScreen: @Composable () -> Unit = {},
    observationDetailScreen: @Composable (eventId: String, onBackClick: () -> Unit) -> Unit = { _, _ -> },
    settingsScreen: @Composable (
        onBackClick: () -> Unit,
        onNavigateToPeers: () -> Unit,
        onNavigateToMetrics: () -> Unit
    ) -> Unit = { onBack, onPeers, onMetrics ->
        SettingsScreen(
            onBackClick = onBack,
            onNavigateToPeers = onPeers,
            onNavigateToMetrics = onMetrics
        )
    }
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isSecondaryView = currentRoute?.startsWith("observation_detail") == true ||
            currentRoute == Screen.Settings.route ||
            currentRoute == Screen.Peers.route ||
            currentRoute == Screen.Metrics.route

    CompositionLocalProvider(LocalNavController provides navController) {
        Scaffold(
            bottomBar = {
                if (!isSecondaryView) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.heightIn(min = 64.dp)
                ) {
                    bottomNavScreens.forEach { screen ->
                        val selected = when (screen.route) {
                            "live_map" -> currentRoute == "live_map" || currentRoute == "map"
                            "reports" -> currentRoute == "reports" || currentRoute == "observations"
                            "chat" -> currentRoute == "chat" || currentRoute?.startsWith("chat?") == true
                            else -> currentRoute == screen.route
                        }

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
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
                            label = {
                                Text(
                                    text = screen.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Home.route) {
                homeScreen(
                    { eventId -> navController.navigate(Screen.observationDetail(eventId)) },
                    { linkedEventId ->
                        if (linkedEventId != null) navController.navigate(Screen.chatWithEvent(linkedEventId))
                        else navController.navigate(Screen.Chat.route)
                    },
                    { navController.navigate(Screen.LiveMap.route) },
                    { navController.navigate(Screen.Reports.route) }
                )
            }

            composable(Screen.LiveMap.route) {
                liveMapScreen(
                    { eventId -> navController.navigate(Screen.observationDetail(eventId)) },
                    { eventId -> navController.navigate(Screen.chatWithEvent(eventId)) }
                )
            }
            // Alias for backwards compatibility
            composable(Screen.Map.route) {
                liveMapScreen(
                    { eventId -> navController.navigate(Screen.observationDetail(eventId)) },
                    { eventId -> navController.navigate(Screen.chatWithEvent(eventId)) }
                )
            }

            composable(Screen.OfflineMaps.route) {
                offlineMapsScreen {
                    navController.navigate(Screen.LiveMap.route)
                }
            }

            composable(Screen.Reports.route) {
                reportsScreen { eventId ->
                    navController.navigate(Screen.observationDetail(eventId))
                }
            }
            // Alias for backwards compatibility
            composable(Screen.Observations.route) {
                reportsScreen { eventId ->
                    navController.navigate(Screen.observationDetail(eventId))
                }
            }

            composable(Screen.Chat.route) {
                chatScreen(null) { eventId ->
                    navController.navigate(Screen.LiveMap.route)
                }
            }
            composable(
                route = Screen.CHAT_WITH_EVENT_ROUTE,
                arguments = listOf(
                    navArgument("eventId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId")
                chatScreen(eventId) { _ ->
                    navController.navigate(Screen.LiveMap.route)
                }
            }

            composable(Screen.Peers.route) { peersScreen() }
            composable(Screen.Metrics.route) { metricsScreen() }

            composable(Screen.Settings.route) {
                settingsScreen(
                    { navController.popBackStack() },
                    { navController.navigate(Screen.Peers.route) },
                    { navController.navigate(Screen.Metrics.route) }
                )
            }

            composable(
                route = Screen.OBSERVATION_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("eventId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId") ?: ""
                observationDetailScreen(eventId) {
                    navController.popBackStack()
                }
            }
        }
    }
    }
}

/**
 * Backwards-compatible overload for 7-parameter calls (from Gate 2 MainActivity).
 */
@Composable
fun RescueMeshNavHost(
    navController: NavHostController = rememberNavController(),
    homeScreen: @Composable (onNavigateToObservation: (String) -> Unit) -> Unit,
    observationsScreen: @Composable () -> Unit,
    mapScreen: @Composable (onNavigateToObservation: (String) -> Unit) -> Unit,
    peersScreen: @Composable () -> Unit,
    metricsScreen: @Composable () -> Unit,
    observationDetailScreen: @Composable (eventId: String, onBackClick: () -> Unit) -> Unit,
    settingsScreen: @Composable (
        onBackClick: () -> Unit,
        onNavigateToPeers: () -> Unit,
        onNavigateToMetrics: () -> Unit
    ) -> Unit = { onBack, onPeers, onMetrics ->
        SettingsScreen(
            onBackClick = onBack,
            onNavigateToPeers = onPeers,
            onNavigateToMetrics = onMetrics
        )
    }
) = RescueMeshNavHost(
    navController = navController,
    homeScreen = { onNavigateToObs, _, _, _ -> homeScreen(onNavigateToObs) },
    liveMapScreen = { onNavigateToObs, _ -> mapScreen(onNavigateToObs) },
    offlineMapsScreen = { onNavigateToSituational ->
        OfflineMapsScreen(onNavigateToSituationalCanvas = onNavigateToSituational)
    },
    reportsScreen = { _ -> observationsScreen() },
    chatScreen = { linkedEventId, onNavigateToMap ->
        ChatScreen(initialLinkedEventId = linkedEventId, onNavigateToMap = onNavigateToMap)
    },
    peersScreen = peersScreen,
    metricsScreen = metricsScreen,
    observationDetailScreen = observationDetailScreen,
    settingsScreen = settingsScreen
)

/**
 * Backward-compatible overload for callers that don't need observation detail navigation.
 */
@Composable
fun RescueMeshNavHost(
    navController: NavHostController = rememberNavController(),
    homeScreen: @Composable () -> Unit,
    observationsScreen: @Composable () -> Unit,
    mapScreen: @Composable () -> Unit,
    peersScreen: @Composable () -> Unit,
    metricsScreen: @Composable () -> Unit
) = RescueMeshNavHost(
    navController = navController,
    homeScreen = { homeScreen() },
    observationsScreen = observationsScreen,
    mapScreen = { mapScreen() },
    peersScreen = peersScreen,
    metricsScreen = metricsScreen,
    observationDetailScreen = { _, _ -> }
)
