package pl.poznan.offdojade

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GtfsImporter
import pl.poznan.offdojade.data.gtfs.PrebuiltDbDownloader
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.model.AppSettings
import pl.poznan.offdojade.domain.model.ThemeMode
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.ui.details.RouteDetailsScreen
import pl.poznan.offdojade.ui.home.HomeScreen
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.localization.stringsFor
import pl.poznan.offdojade.ui.onboarding.OnboardingScreen
import pl.poznan.offdojade.ui.results.RouteResultsScreen
import pl.poznan.offdojade.ui.settings.SettingsScreen
import pl.poznan.offdojade.ui.theme.OffDojadeTheme
import pl.poznan.offdojade.ui.timetables.TimetablesScreen

class MainActivity : ComponentActivity() {

    private val realtimeRepository: RealtimeRepository by inject()
    private val transitGraphRepository: TransitGraphRepository by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val prebuiltDbDownloader: PrebuiltDbDownloader by inject()
    private val gtfsImporter: GtfsImporter by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            transitGraphRepository.ensureLoadedForToday()
            checkAndAutoUpdateScheduleInBackground()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                realtimeRepository.pollLoop()
            }
        }

        setContent {
            val appSettings by preferencesRepository.appSettings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            CompositionLocalProvider(LocalStrings provides stringsFor(appSettings.language)) {
                OffDojadeTheme(darkTheme = darkTheme) {
                    val onboardingDone by preferencesRepository.onboardingCompleted
                        .collectAsStateWithLifecycle(initialValue = null)

                    RequestLocationPermissionOnce()

                    if (onboardingDone != null) {
                        OffDojadeNavGraph(
                            startDestination = if (onboardingDone == true) Routes.HOME else Routes.ONBOARDING
                        )
                    }
                }
            }
        }
    }

    /**
     * Тихая проверка обновлений расписания ZTM при запуске приложения (в фоне на IO):
     * если вышла новая версия маршрутов/расписания — автоматически скачивает и обновляет граф.
     */
    private fun checkAndAutoUpdateScheduleInBackground() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val hasData = transitGraphRepository.hasDatabaseData()
                val localMeta = preferencesRepository.getSyncMetadata()

                if (AppConfig.isPrebuiltScheduleConfigured()) {
                    val remote = prebuiltDbDownloader.fetchRemoteVersion()
                    if (remote != null) {
                        val isSame = hasData &&
                            localMeta.prebuiltDbSha256 != null &&
                            localMeta.prebuiltDbSha256.equals(remote.sha256, ignoreCase = true)
                        if (!isSame) {
                            prebuiltDbDownloader.syncFromGitHubReleases().collect()
                            transitGraphRepository.reloadForToday()
                        }
                        return@runCatching
                    }
                }

                val headers = gtfsImporter.fetchRemoteHeaders() ?: return@runCatching
                val unchanged = hasData && (
                    (!headers.etag.isNullOrBlank() && headers.etag == localMeta.etag) ||
                        (!headers.lastModified.isNullOrBlank() && headers.lastModified == localMeta.lastModified) ||
                        (!headers.archiveName.isNullOrBlank() && headers.archiveName == localMeta.archiveName)
                    )
                if (!unchanged) {
                    gtfsImporter.downloadAndImport().collect()
                    transitGraphRepository.reloadForToday()
                }
            }
        }
    }
}

@Composable
private fun RequestLocationPermissionOnce() {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { }
    )
    LaunchedEffect(Unit) {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val RESULTS = "results"
    const val DETAILS = "details"
    const val SETTINGS_TAB = "settings_tab"
    const val SETTINGS_PUSH = "settings_push"
    const val TIMETABLES = "timetables"
}

@Composable
private fun OffDojadeNavGraph(startDestination: String) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val s = LocalStrings.current

    val tabRoutes = setOf(Routes.HOME, Routes.TIMETABLES, Routes.SETTINGS_TAB)
    val showBottomBar = currentRoute in tabRoutes

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        val navColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.HOME,
                            onClick = {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Directions, contentDescription = s.navRoute) },
                            label = { Text(s.navRoute) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.TIMETABLES,
                            onClick = {
                                navController.navigate(Routes.TIMETABLES) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Schedule, contentDescription = s.timetables) },
                            label = { Text(s.timetables) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = currentRoute == Routes.SETTINGS_TAB,
                            onClick = {
                                navController.navigate(Routes.SETTINGS_TAB) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Default.Tune, contentDescription = s.settings) },
                            label = { Text(s.settings) },
                            colors = navColors
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = {
                    val fromTab = initialState.destination.route in tabRoutes
                    val toTab = targetState.destination.route in tabRoutes
                    if (fromTab && toTab) {
                        fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                    } else {
                        slideInHorizontally(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            initialOffsetX = { it / 7 }
                        ) + fadeIn(animationSpec = tween(220))
                    }
                },
                exitTransition = {
                    val fromTab = initialState.destination.route in tabRoutes
                    val toTab = targetState.destination.route in tabRoutes
                    if (fromTab && toTab) {
                        fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing))
                    } else {
                        slideOutHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            targetOffsetX = { -it / 12 }
                        ) + fadeOut(animationSpec = tween(180))
                    }
                },
                popEnterTransition = {
                    slideInHorizontally(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetX = { -it / 10 }
                    ) + fadeIn(animationSpec = tween(220))
                },
                popExitTransition = {
                    slideOutHorizontally(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                        targetOffsetX = { it / 7 }
                    ) + fadeOut(animationSpec = tween(180))
                }
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onFinished = {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenRouteResults = {
                            navController.navigate(Routes.RESULTS)
                        },
                        onOpenSyncSettings = {
                            navController.navigate(Routes.ONBOARDING)
                        },
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS_PUSH)
                        }
                    )
                }

                composable(Routes.RESULTS) {
                    RouteResultsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDetails = { navController.navigate(Routes.DETAILS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS_PUSH) }
                    )
                }

                composable(Routes.DETAILS) {
                    RouteDetailsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(Routes.SETTINGS_TAB) {
                    SettingsScreen(
                        showBackButton = false,
                        onBack = {},
                        onOpenSyncAndMap = { navController.navigate(Routes.ONBOARDING) }
                    )
                }

                composable(Routes.SETTINGS_PUSH) {
                    SettingsScreen(
                        showBackButton = true,
                        onBack = { navController.popBackStack() },
                        onOpenSyncAndMap = { navController.navigate(Routes.ONBOARDING) }
                    )
                }

                composable(Routes.TIMETABLES) {
                    TimetablesScreen(
                        showBackButton = false,
                        onBack = { navController.popBackStack() },
                        onOpenSync = { navController.navigate(Routes.ONBOARDING) }
                    )
                }
            }
        }
    }
}
