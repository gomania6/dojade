package pl.poznan.offdojade.config

/**
 * Централизованная конфигурация всех URL-источников и параметров Zero-Backend архитектуры.
 */
object AppConfig {

    // -------------------------------------------------------------------------
    // 1. ZTM Poznań — Статический GTFS (расписания, остановки, тарифные зоны)
    // -------------------------------------------------------------------------
    /** Каталог файлов открытых данных ZTM Poznań */
    const val ZTM_GTFS_CATALOG_URL = "https://www.ztm.poznan.pl/pl/dla-deweloperow/gtfsFiles"

    /** Страница открытых данных ZTM Poznań */
    const val ZTM_OPEN_DATA_URL = "https://www.ztm.poznan.pl/otwarte-dane"

    /**
     * Прямая ссылка на актуальный ZIP-архив GTFS ZTM Poznań.
     * При отсутствии параметра `file` сервер ZTM всегда отдает самый свежий архив.
     */
    const val ZTM_GTFS_DIRECT_ZIP_URL = "https://www.ztm.poznan.pl/pl/dla-deweloperow/getGTFSFile"

    // -------------------------------------------------------------------------
    // 2. ZTM Poznań — Real-Time GTFS-RT (без API-ключей)
    // -------------------------------------------------------------------------
    /** Прогноз прибытия и текущие задержки рейсов (Protobuf) */
    const val ZTM_GTFS_RT_TRIP_UPDATES_URL =
        "https://www.ztm.poznan.pl/pl/dla-deweloperow/getGtfsRtFile?file=trip_updates.pb"

    /** Текущие GPS-координаты трамваев и автобусов (Protobuf) */
    const val ZTM_GTFS_RT_VEHICLE_POSITIONS_URL =
        "https://www.ztm.poznan.pl/pl/dla-deweloperow/getGtfsRtFile?file=vehicle_positions.pb"

    // -------------------------------------------------------------------------
    // 3. Офлайн-векторная карта (GitHub Releases — PMTiles)
    // -------------------------------------------------------------------------
    /**
     * Ссылка на релиз GitHub с офлайн-векторной картой Познани (.pmtiles).
     * >>> ЗАМЕНИТЕ `USER/REPO` НА ВАШ РЕПОЗИТОРИЙ GITHUB С РЕЛИЗОМ `poznan.pmtiles` <<<
     */
    const val MAP_DOWNLOAD_URL =
        "https://github.com/gomania6/dojade/releases/download/map-v1/poznan.pmtiles"

    /** Локальное имя файла карты в `context.filesDir` */
    const val LOCAL_MAP_FILENAME = "poznan.pmtiles"

    // -------------------------------------------------------------------------
    // 4. «Бесплатный сервер» через GitHub Actions (готовая SQLite-база)
    // -------------------------------------------------------------------------
    /** Метаданные ночной сборки расписания (~100 байт). */
    const val SCHEDULE_VERSION_URL =
        "https://github.com/gomania6/dojade/releases/download/schedule-latest/version.json"

    /** Сжатая готовая SQLite-база расписаний с индексами и пешими пересадками. */
    const val SCHEDULE_DB_GZ_URL =
        "https://github.com/gomania6/dojade/releases/download/schedule-latest/poznan_schedule.db.gz"

    /** Имя локального файла базы Room */
    const val DATABASE_NAME = "ztm_poznan_gtfs.db"

    /** Проверяет, подставил ли пользователь свой репозиторий вместо плейсхолдера USER/REPO */
    fun isPrebuiltScheduleConfigured(): Boolean =
        !SCHEDULE_VERSION_URL.contains("/USER/REPO/") && !SCHEDULE_DB_GZ_URL.contains("/USER/REPO/")

    // -------------------------------------------------------------------------
    // Параметры движка маршрутизации и синхронизации
    // -------------------------------------------------------------------------
    /** Интервал опроса GTFS-RT в миллисекундах (20 секунд) */
    const val GTFS_RT_POLL_INTERVAL_MS = 20_000L

    /** Максимальный радиус пеших пересадок (footpaths) между остановками, в метрах */
    const val FOOTPATH_MAX_DISTANCE_METERS = 300.0

    /** Максимальный радиус пешего подхода от GPS пользователя до начальной остановки, в метрах */
    const val ORIGIN_WALK_MAX_DISTANCE_METERS = 600.0

    /** Средняя скорость пешехода, м/с */
    const val WALKING_SPEED_MPS = 1.2

    /** Максимум пересадок в алгоритме RAPTOR */
    const val RAPTOR_MAX_TRANSFERS = 3

    /** Размер батча при потоковой вставке CSV в Room */
    const val GTFS_DB_BATCH_SIZE = 3_000

    /** Координаты центра Познани (Stary Rynek / Rondo Kaponiera) по умолчанию */
    const val POZNAN_CENTER_LAT = 52.4082
    const val POZNAN_CENTER_LON = 16.9125
}
