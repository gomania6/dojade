package pl.poznan.offdojade

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre
import pl.poznan.offdojade.data.worker.GtfsSyncWorker
import pl.poznan.offdojade.di.appModule

class OffDojadeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        startKoin {
            androidContext(this@OffDojadeApp)
            modules(appModule)
        }
        GtfsSyncWorker.scheduleDailyWifiSync(this)
    }
}
