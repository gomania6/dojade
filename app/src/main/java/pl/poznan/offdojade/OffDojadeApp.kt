package pl.poznan.offdojade

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre
import pl.poznan.offdojade.data.worker.GtfsSyncWorker
import pl.poznan.offdojade.di.appModule
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalDate
import java.time.ZoneId

class OffDojadeApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        startKoin {
            androidContext(this@OffDojadeApp)
            modules(appModule)
        }
        // Планируем фоновую автосинхронизацию оффлайн-расписания каждые 12 часов
        GtfsSyncWorker.scheduleBackgroundSync(this)

        // Заранее прогреваем граф RAPTOR (на сегодня и на завтра) и кэш остановок сразу при старте процесса,
        // чтобы первый поиск (включая вечерний/завтрашний) срабатывал мгновенно
        val graphRepo: TransitGraphRepository = get()
        appScope.launch {
            runCatching {
                graphRepo.ensureLoadedForToday()
                graphRepo.searchStopGroups("", limit = 1)
                val tomorrow = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(1)
                graphRepo.buildGraphForDate(tomorrow)
            }
        }
    }
}
