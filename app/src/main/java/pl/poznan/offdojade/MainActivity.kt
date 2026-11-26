package pl.poznan.offdojade

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.ui.details.RouteDetailsScreen
import pl.poznan.offdojade.ui.home.HomeScreen
import pl.poznan.offdojade.ui.onboarding.OnboardingScreen
import pl.poznan.offdojade.ui.results.RouteResultsScreen
import pl.poznan.offdojade.ui.theme.OffDojadeTheme

class MainActivity : ComponentActivity() {

    private val realtimeRepository: RealtimeRepository by inject()
    private val transitGraphRepository: TransitGraphRepository by inject()
    private val preferencesRepository: PreferencesRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Предзагрузка сегодняшнего расписания в In-Memory RAPTOR-граф при старте
        lifecycleScope.launch {
            transitGraphRepository.ensureLoadedForToday()
        }

        // Живой опрос GTFS-RT каждые 20 секунд, только пока приложение на переднем плане (STARTED)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                realtimeRepository.pollLoop()
            }
        }

        setContent {
            OffDojadeTheme {
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
    const val RESULTS = "results/{stopId}?fromStopId={fromStopId}"
    const val DETAILS = "details"

    fun results(fromStopId: String?, stopId: String): String {
        val base = "results/${Uri.encode(stopId)}"
        return if (!fromStopId.isNullOrBlank()) {
            "$base?fromStopId=${Uri.encode(fromStopId)}"
        } else {
            base
        }
    }
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
                onOpenRouteResults = { fromStopId, targetStopId ->
                    navController.navigate(Routes.results(fromStopId, targetStopId))
                },
                onOpenSyncSettings = {
                    navController.navigate(Routes.ONBOARDING)
                }
            )
        }

        composable(
            route = Routes.RESULTS,
            arguments = listOf(
                navArgument("stopId") { type = NavType.StringType },
                navArgument("fromStopId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val stopId = backStackEntry.arguments?.getString("stopId").orEmpty()
            val fromStopId = backStackEntry.arguments?.getString("fromStopId")
            RouteResultsScreen(
                fromStopId = fromStopId,
                targetStopId = stopId,
                onBack = { navController.popBackStack() },
                onOpenDetails = { navController.navigate(Routes.DETAILS) }
            )
        }

        composable(Routes.DETAILS) {
            RouteDetailsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
