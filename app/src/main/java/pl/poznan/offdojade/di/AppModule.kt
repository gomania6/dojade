package pl.poznan.offdojade.di

import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import pl.poznan.offdojade.data.gtfs.GtfsImporter
import pl.poznan.offdojade.data.gtfs.PrebuiltDbDownloader
import pl.poznan.offdojade.data.local.DatabaseHolder
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.location.AddressSearchProvider
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.location.WalkingRouteProvider
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.data.realtime.GtfsRtClient
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.ui.details.RouteDetailsViewModel
import pl.poznan.offdojade.ui.home.HomeViewModel
import pl.poznan.offdojade.ui.onboarding.OnboardingViewModel
import pl.poznan.offdojade.ui.results.RouteResultsViewModel
import pl.poznan.offdojade.ui.settings.SettingsViewModel
import pl.poznan.offdojade.ui.timetables.TimetablesViewModel
import java.util.concurrent.TimeUnit

val appModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    single { DatabaseHolder(androidContext()) }
    single { PreferencesRepository(androidContext()) }
    single { LocationProvider(androidContext()) }
    single { AddressSearchProvider(androidContext(), get()) }
    single { WalkingRouteProvider(get()) }
    single { MapDownloader(androidContext(), get()) }
    single { PrebuiltDbDownloader(androidContext(), get(), get(), get()) }
    single { GtfsImporter(get(), get(), get()) }
    single { GtfsRtClient(get()) }
    single { RealtimeRepository(get()) }
    single { RaptorEngine() }
    single { TransitGraphRepository(get()) }

    viewModel { OnboardingViewModel(get(), get(), get(), get(), get()) }
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { RouteResultsViewModel(get(), get(), get(), get(), get()) }
    viewModel { RouteDetailsViewModel(get(), get(), get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { TimetablesViewModel(get()) }
}
