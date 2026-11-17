package com.example

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.CommanderViewModel
import com.example.ui.animations.rememberReducedMotion
import com.example.ui.components.SpaceBackdrop
import com.example.ui.screens.AssistantHubScreen
import com.example.ui.screens.CameraAssistantScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.ModelsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsScreen
import com.example.ui.theme.CommanderTheme
import com.example.ui.theme.DeepSpaceBlack
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElevatedNavy
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.TextSecondary
import kotlinx.serialization.Serializable

@Serializable object DashboardRoute
@Serializable object ModelsRoute
@Serializable object ToolsRoute
@Serializable object HistoryRoute
@Serializable object SettingsRoute
@Serializable data class AssistantHubRoute(val initialTab: Int = 0)
@Serializable object CameraAiRoute

private data class NavItem<T : Any>(
    val route: T,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CommanderTheme {
                val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
                CommanderAppShell(widthSizeClass = widthSizeClass)
            }
        }
    }
}

@Composable
fun CommanderAppShell(
    widthSizeClass: WindowWidthSizeClass,
    viewModel: CommanderViewModel = viewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMsg by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onScreenCaptureResult(result.resultCode, result.data)
    }

    val requestScreenCapture: () -> Unit = {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val triggerExplainScreen: () -> Unit = {
        if (viewModel.canExplainScreenWithoutDialog()) {
            viewModel.submitCommand("Explain my screen")
        } else {
            requestScreenCapture()
        }
    }

    val navItems = remember {
        listOf(
            NavItem(DashboardRoute, "Command", Icons.Filled.Home, Icons.Outlined.Home, "nav_dashboard"),
            NavItem(ModelsRoute, "Models", Icons.Filled.Memory, Icons.Outlined.Memory, "nav_models"),
            NavItem(ToolsRoute, "Tools", Icons.Filled.Build, Icons.Outlined.Build, "nav_tools"),
            NavItem(HistoryRoute, "History", Icons.Filled.History, Icons.Outlined.History, "nav_history"),
            NavItem(SettingsRoute, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
        )
    }

    val useRail = widthSizeClass != WindowWidthSizeClass.Compact

    fun <T : Any> navigateTab(route: T) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    SpaceBackdrop {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MidnightNavy.copy(alpha = 0.96f)) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElevatedNavy,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(containerColor = DeepSpaceBlack.copy(alpha = 0.92f)) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElevatedNavy,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                val reducedMotion = rememberReducedMotion()
                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = DashboardRoute,
                        enterTransition = {
                            if (reducedMotion) fadeIn(tween(80))
                            else fadeIn(tween(200)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 16 }
                        },
                        exitTransition = {
                            if (reducedMotion) fadeOut(tween(80))
                            else fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { -it / 16 }
                        },
                        popEnterTransition = {
                            if (reducedMotion) fadeIn(tween(80))
                            else fadeIn(tween(200)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 16 }
                        },
                        popExitTransition = {
                            if (reducedMotion) fadeOut(tween(80))
                            else fadeOut(tween(180)) + slideOutHorizontally(tween(200)) { it / 16 }
                        }
                    ) {
                        composable<DashboardRoute> {
                            DashboardScreen(
                                viewModel = viewModel,
                                onRequestScreenCapture = triggerExplainScreen,
                                onNavigateToModels = { navigateTab(ModelsRoute) },
                                onNavigateToSettings = { navigateTab(SettingsRoute) },
                                onNavigateToHub = { tab -> navController.navigate(AssistantHubRoute(tab)) },
                                onNavigateToCamera = { navController.navigate(CameraAiRoute) }
                            )
                        }
                        composable<ModelsRoute> {
                            ModelsScreen(viewModel = viewModel)
                        }
                        composable<ToolsRoute> {
                            ToolsScreen(
                                viewModel = viewModel,
                                onRequestScreenCapture = triggerExplainScreen,
                                onNavigateToHub = { tab -> navController.navigate(AssistantHubRoute(tab)) },
                                onNavigateToCamera = { navController.navigate(CameraAiRoute) }
                            )
                        }
                        composable<HistoryRoute> {
                            HistoryScreen(
                                viewModel = viewModel,
                                onRepeatNavigateDashboard = { navigateTab(DashboardRoute) }
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                viewModel = viewModel,
                                onRequestScreenCapture = requestScreenCapture
                            )
                        }
                        composable<AssistantHubRoute> { entry ->
                            val route = entry.toRoute<AssistantHubRoute>()
                            AssistantHubScreen(
                                viewModel = viewModel,
                                initialTab = route.initialTab,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable<CameraAiRoute> {
                            CameraAssistantScreen(
                                viewModel = viewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
