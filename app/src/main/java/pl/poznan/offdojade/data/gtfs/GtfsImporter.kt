package pl.poznan.offdojade.data.gtfs

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.DatabaseHolder
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.local.entity.AgencyEntity
import pl.poznan.offdojade.data.local.entity.CalendarDateEntity
import pl.poznan.offdojade.data.local.entity.CalendarEntity
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class GtfsImportProgress(
    val stage: String,
    val progress: Float,
    val rowsImported: Int = 0,
    val isFinished: Boolean = false,
    val errorMessage: String? = null
)

data class RemoteGtfsHeaders(
    val etag: String?,
    val lastModified: String?,
    val archiveName: String?
)

/**
 * Потоковый импортер GTFS ZIP в Room.
 * Никогда не загружает `stop_times.txt` целиком в память — читает `ZipInputStream`
 * построчно через `BufferedReader` и вставляет батчами по [AppConfig.GTFS_DB_BATCH_SIZE]
 * внутри `@Transaction`.
 */
class GtfsImporter(
    private val databaseHolder: DatabaseHolder,
    private val okHttpClient: OkHttpClient,
    private val preferencesRepository: PreferencesRepository
) {
    private val database get() = databaseHolder.database
    private val dao get() = databaseHolder.dao

    /**
     * Выполняет HEAD-запрос к ZTM Poznań для проверки, обновился ли архив.
     */
    fun fetchRemoteHeaders(url: String = AppConfig.ZTM_GTFS_DIRECT_ZIP_URL): RemoteGtfsHeaders? {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Accept", "application/octet-stream")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                RemoteGtfsHeaders(
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    archiveName = parseFileNameFromDisposition(response.header("Content-Disposition"))
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Скачивает актуальный ZIP-архив ZTM Poznań и импортирует его в Room в потоковом режиме.
     */
    fun downloadAndImport(
        url: String = AppConfig.ZTM_GTFS_DIRECT_ZIP_URL
    ): Flow<GtfsImportProgress> = flow {
        emit(GtfsImportProgress("Подключение к ZTM Poznań…", 0.02f))

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} при загрузке GTFS ZTM Poznań")
            }
            val body = resp.body ?: throw IOException("Пустой ответ от сервера ZTM")
            val etag = resp.header("ETag")
            val lastModified = resp.header("Last-Modified")
            val archiveName = parseFileNameFromDisposition(resp.header("Content-Disposition"))

            importFromZipStream(body.byteStream()) { stage, progress, rows ->
                emit(GtfsImportProgress(stage = stage, progress = progress, rowsImported = rows))
            }

            preferencesRepository.updateSyncMetadata(
                etag = etag,
                lastModified = lastModified,
                archiveName = archiveName
            )
            emit(GtfsImportProgress("Расписание ZTM обновлено", 1f, isFinished = true))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Потоковый импорт из произвольного `InputStream` с GTFS ZIP (используется и в тестах).
     */
    suspend fun importFromZipStream(
        inputStream: InputStream,
        onProgress: suspend (stage: String, progress: Float, rowsImported: Int) -> Unit = { _, _, _ -> }
    ) {
        onProgress("Очистка старого расписания…", 0.05f, 0)
        dao.clearAllGtfsData()

        var totalRows = 0
        val fileWeights = mapOf(
            "agency.txt" to 0.08f,
            "routes.txt" to 0.14f,
            "stops.txt" to 0.24f,
            "calendar.txt" to 0.30f,
            "calendar_dates.txt" to 0.36f,
            "trips.txt" to 0.48f,
            "stop_times.txt" to 0.78f,
            "shapes.txt" to 0.88f,
            "transfers.txt" to 0.92f
        )

        ZipInputStream(BufferedInputStream(inputStream, 32 * 1024)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    val progressTarget = fileWeights[fileName]
                    if (progressTarget != null) {
                        onProgress("Импорт $fileName…", progressTarget, totalRows)
                        val reader = BufferedReader(InputStreamReader(zis, StandardCharsets.UTF_8), 32 * 1024)
                        val imported = when (fileName) {
                            "agency.txt" -> streamCsvBatches(reader, ::parseAgency) { batch ->
                                dao.insertAgencies(batch)
                            }

                            "routes.txt" -> streamCsvBatches(reader, ::parseRoute) { batch ->
                                dao.insertRoutes(batch)
                            }

                            "stops.txt" -> streamCsvBatches(reader, ::parseStop) { batch ->
                                dao.insertStops(batch)
                            }

                            "calendar.txt" -> streamCsvBatches(reader, ::parseCalendar) { batch ->
                                dao.insertCalendars(batch)
                            }

                            "calendar_dates.txt" -> streamCsvBatches(reader, ::parseCalendarDate) { batch ->
                                dao.insertCalendarDates(batch)
                            }

                            "trips.txt" -> streamCsvBatches(reader, ::parseTrip) { batch ->
                                dao.insertTrips(batch)
                            }

                            "stop_times.txt" -> streamCsvBatches(reader, ::parseStopTime) { batch ->
                                dao.insertStopTimes(batch)
                            }

                            "shapes.txt" -> streamCsvBatches(reader, ::parseShapePoint) { batch ->
                                dao.insertShapes(batch)
                            }

                            "transfers.txt" -> streamCsvBatches(reader, ::parseTransfer) { batch ->
                                dao.insertTransfers(batch)
                            }

                            else -> 0
                        }
                        totalRows += imported
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Генерация пеших пересадок (< 300 м) после загрузки stops.txt и transfers.txt
        onProgress("Расчет пеших пересадок (< 300 м)…", 0.94f, totalRows)
        val allStops = dao.getAllStops()
        val existingTransfers = dao.getAllTransfers()
            .map { it.fromStopId to it.toStopId }
            .toHashSet()
        val generatedFootpaths = FootpathGenerator.generateFootpaths(
            stops = allStops,
            existingPairs = existingTransfers
        )
        if (generatedFootpaths.isNotEmpty()) {
            generatedFootpaths.chunked(AppConfig.GTFS_DB_BATCH_SIZE).forEach { chunk ->
                database.withTransaction {
                    dao.insertGeneratedFootpaths(chunk)
                }
            }
        }
        onProgress("Готово", 0.99f, totalRows + generatedFootpaths.size)
    }

    private suspend fun <T> streamCsvBatches(
        reader: BufferedReader,
        mapper: (Map<String, Int>, List<String>) -> T?,
        insertBatch: suspend (List<T>) -> Unit
    ): Int {
        val headerLine = reader.readLine() ?: return 0
        val header = CsvLineParser.parseHeader(headerLine)
        val batch = ArrayList<T>(AppConfig.GTFS_DB_BATCH_SIZE)
        var count = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cols = CsvLineParser.parseLine(line)
            val entity = mapper(header, cols)
            if (entity != null) {
                batch.add(entity)
                if (batch.size >= AppConfig.GTFS_DB_BATCH_SIZE) {
                    val toInsert = ArrayList(batch)
                    database.withTransaction { insertBatch(toInsert) }
                    count += toInsert.size
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            database.withTransaction { insertBatch(batch) }
            count += batch.size
            batch.clear()
        }
        return count
    }

    private fun Map<String, Int>.col(cols: List<String>, name: String): String {
        val idx = this[name] ?: return ""
        return if (idx in cols.indices) cols[idx].trim() else ""
    }

    private fun parseAgency(h: Map<String, Int>, c: List<String>): AgencyEntity? {
        val id = h.col(c, "agency_id").ifEmpty { "ztm_poznan" }
        val name = h.col(c, "agency_name").ifEmpty { return null }
        return AgencyEntity(
            agencyId = id,
            agencyName = name,
            agencyUrl = h.col(c, "agency_url"),
            agencyTimezone = h.col(c, "agency_timezone").ifEmpty { "Europe/Warsaw" }
        )
    }

    private fun parseRoute(h: Map<String, Int>, c: List<String>): RouteEntity? {
        val id = h.col(c, "route_id").ifEmpty { return null }
        return RouteEntity(
            routeId = id,
            agencyId = h.col(c, "agency_id"),
            shortName = h.col(c, "route_short_name").ifEmpty { id },
            longName = h.col(c, "route_long_name"),
            routeType = h.col(c, "route_type").toIntOrNull() ?: 3,
            routeColor = h.col(c, "route_color").ifEmpty { null },
            routeTextColor = h.col(c, "route_text_color").ifEmpty { null }
        )
    }

    private fun parseStop(h: Map<String, Int>, c: List<String>): StopEntity? {
        val id = h.col(c, "stop_id").ifEmpty { return null }
        val lat = h.col(c, "stop_lat").toDoubleOrNull() ?: return null
        val lon = h.col(c, "stop_lon").toDoubleOrNull() ?: return null
        return StopEntity(
            stopId = id,
            stopCode = h.col(c, "stop_code"),
            stopName = h.col(c, "stop_name").ifEmpty { id },
            stopLat = lat,
            stopLon = lon,
            zoneId = h.col(c, "zone_id").ifEmpty { "A" },
            platformCode = h.col(c, "platform_code")
        )
    }

    private fun parseCalendar(h: Map<String, Int>, c: List<String>): CalendarEntity? {
        val serviceId = h.col(c, "service_id").ifEmpty { return null }
        val startDate = h.col(c, "start_date").toIntOrNull() ?: return null
        val endDate = h.col(c, "end_date").toIntOrNull() ?: return null
        return CalendarEntity(
            serviceId = serviceId,
            monday = h.col(c, "monday") == "1",
            tuesday = h.col(c, "tuesday") == "1",
            wednesday = h.col(c, "wednesday") == "1",
            thursday = h.col(c, "thursday") == "1",
            friday = h.col(c, "friday") == "1",
            saturday = h.col(c, "saturday") == "1",
            sunday = h.col(c, "sunday") == "1",
            startDate = startDate,
            endDate = endDate
        )
    }

    private fun parseCalendarDate(h: Map<String, Int>, c: List<String>): CalendarDateEntity? {
        val serviceId = h.col(c, "service_id").ifEmpty { return null }
        val date = h.col(c, "date").toIntOrNull() ?: return null
        val exceptionType = h.col(c, "exception_type").toIntOrNull() ?: return null
        return CalendarDateEntity(
            serviceId = serviceId,
            date = date,
            exceptionType = exceptionType
        )
    }

    private fun parseTrip(h: Map<String, Int>, c: List<String>): TripEntity? {
        val tripId = h.col(c, "trip_id").ifEmpty { return null }
        val routeId = h.col(c, "route_id").ifEmpty { return null }
        val serviceId = h.col(c, "service_id").ifEmpty { return null }
        return TripEntity(
            tripId = tripId,
            routeId = routeId,
            serviceId = serviceId,
            tripHeadsign = h.col(c, "trip_headsign"),
            directionId = h.col(c, "direction_id").toIntOrNull() ?: 0,
            shapeId = h.col(c, "shape_id"),
            wheelchairAccessible = h.col(c, "wheelchair_accessible").toIntOrNull() ?: 0
        )
    }

    private fun parseStopTime(h: Map<String, Int>, c: List<String>): StopTimeEntity? {
        val tripId = h.col(c, "trip_id").ifEmpty { return null }
        val stopId = h.col(c, "stop_id").ifEmpty { return null }
        val seq = h.col(c, "stop_sequence").toIntOrNull() ?: return null
        val arr = CsvLineParser.parseGtfsTimeToSeconds(h.col(c, "arrival_time"))
        val dep = CsvLineParser.parseGtfsTimeToSeconds(h.col(c, "departure_time"))
        val arrSec = arr ?: dep ?: return null
        val depSec = dep ?: arrSec
        val pickupType = h.col(c, "pickup_type").toIntOrNull() ?: 0
        val dropOffType = h.col(c, "drop_off_type").toIntOrNull() ?: 0
        val onDemand = if (pickupType == 3 || dropOffType == 3) 1 else 0
        return StopTimeEntity(
            tripId = tripId,
            stopSequence = seq,
            stopId = stopId,
            arrivalTimeSec = arrSec,
            departureTimeSec = depSec,
            isOnDemand = onDemand
        )
    }

    private fun parseShapePoint(h: Map<String, Int>, c: List<String>): ShapePointEntity? {
        val shapeId = h.col(c, "shape_id").ifEmpty { return null }
        val seq = h.col(c, "shape_pt_sequence").toIntOrNull() ?: return null
        val lat = h.col(c, "shape_pt_lat").toDoubleOrNull() ?: return null
        val lon = h.col(c, "shape_pt_lon").toDoubleOrNull() ?: return null
        return ShapePointEntity(
            shapeId = shapeId,
            sequence = seq,
            lat = lat,
            lon = lon
        )
    }

    private fun parseTransfer(h: Map<String, Int>, c: List<String>): TransferEntity? {
        val from = h.col(c, "from_stop_id").ifEmpty { return null }
        val to = h.col(c, "to_stop_id").ifEmpty { return null }
        val type = h.col(c, "transfer_type").toIntOrNull() ?: 0
        val minTime = h.col(c, "min_transfer_time").toIntOrNull() ?: 120
        return TransferEntity(
            fromStopId = from,
            toStopId = to,
            transferType = type,
            minTransferTimeSec = minTime,
            distanceMeters = (minTime * AppConfig.WALKING_SPEED_MPS).toInt()
        )
    }

    private fun parseFileNameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val regex = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE)
        return regex.find(disposition)?.groupValues?.getOrNull(1)
    }
}
