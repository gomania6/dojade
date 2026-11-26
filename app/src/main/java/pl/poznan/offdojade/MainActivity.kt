package pl.poznan.offdojade

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

                // Fallback: прямой HEAD-запрос к ZTM Poznań
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
    const val SETTINGS = "settings"
    const val TIMETABLES = "timetables"
}

@Composable
private fun OffDojadeNavGraph(startDestination: String) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
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
                    navController.navigate(Routes.SETTINGS)
                },
                onOpenTimetables = {
                    navController.navigate(Routes.TIMETABLES)
                }
            )
        }

        composable(Routes.RESULTS) {
            RouteResultsScreen(
                onBack = { navController.popBackStack() },
                onOpenDetails = { navController.navigate(Routes.DETAILS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.DETAILS) {
            RouteDetailsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenSyncAndMap = { navController.navigate(Routes.ONBOARDING) }
            )
        }

        composable(Routes.TIMETABLES) {
            TimetablesScreen(
                onBack = { navController.popBackStack() },
                onOpenSync = { navController.navigate(Routes.ONBOARDING) }
            )
        }
    }
}
