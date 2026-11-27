# AGENT.md — PyrTransit (ZTM Poznań Offline-First Navigator)

Контекстный файл для продолжения работы над проектом в новом чате (`e:\dojade`).

## 1. Обзор проекта
**PyrTransit** — нативное Android-приложение на **Kotlin 2.0.21 + Jetpack Compose (Material 3)** — сверхбыстрый офлайн-навигатор общественного транспорта для Познани и агломерации (**ZTM Poznań**, зоны **A / B / C / D**) без рекламы и собственного бэкенд-сервера.

- **Репозиторий GitHub:** `https://github.com/gomania6/pyrtransit`
- **Package / Application ID:** `pl.poznan.offdojade` (внутренний пакет оставлен прежним, отображаемое имя — **PyrTransit**)
- **SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- **Сборка и среда (ВАЖНО):**
  - На машине по умолчанию стоит Java 25, но **Gradle 8.9 + AGP 8.7.3 требуют JDK 21**.
  - Всегда запускайте Gradle с:
    ```powershell
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
    .\gradlew.bat testDebugUnitTest assembleRelease
    ```
  - Android SDK: `C:\Users\goman\AppData\Local\Android\Sdk` (в `local.properties`).
  - **Релизная подпись:** настроен постоянный PKCS12 keystore `pyrtransit-release.jks` + `keystore.properties` (оба в `.gitignore`, также поддерживаются env-переменные `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` для CI).
  - **Оптимизация релизного APK:** R8 (`isMinifyEnabled = true`, `isShrinkResources = true`), 5 локалей (`ru, uk, pl, en, de`), ABI `arm64-v8a` + `armeabi-v7a` (~24 МБ).
  - **Стек:** Koin 4.0.0, Room 2.6.1 (KSP, **v4**), DataStore Preferences, OkHttp 4.12, Square Wire 5.1.0 (`gtfs-realtime.proto`), MapLibre Native Android 11.6.1 (`pmtiles://file://...`), WorkManager, Play Services Location.

---

## 2. Реализованная архитектура

### A. Конфигурация и тарифы (`config/`, `domain/tariff/`)
- `AppConfig.kt`:
  - Прямой GTFS ZTM Poznań + GTFS-RT (`trip_updates.pb`, `vehicle_positions.pb`).
  - GitHub Releases (`gomania6/pyrtransit`): `schedule-latest/version.json`, `schedule-latest/poznan_schedule.db.gz`, `map-v1/poznan.pmtiles`.
- `ZtmTariffConfig.kt` + `ZtmTicketCalculator.kt`:
  - Временные билеты ZTM (15 / 45 / 90 мин / 24 ч, Normalny и Ulgowy, буфер +5 мин и опоздания).
  - PEKA tPortmonetka с учётом правила продолжения тарифа при пересадке `≤ 20 мин` (`pekaStopGroups`).
  - Автоматическое скрытие цены на маршрутах с линиями **PKS** (`hasNonZtmPksLeg`).

### B. Сетевая безопасность TLS (`res/raw/ztm_certum_ca.pem`, `res/xml/network_security_config.xml`)
- Вшита цепочка УЦ **Certum** строго для `ztm.poznan.pl` (исправляет `CertPathValidatorException` на Android).

### C. Слой данных (`data/`) и GitHub Actions
- **Room v4 (`GtfsDatabase.kt`):** миграции `MIGRATION_1_2` + `MIGRATION_2_3` + `MIGRATION_3_4` (удалены 4 избыточных индекса `stop_times`/`shapes`/`transfers`, экономия ~75–85 МБ в БД), поля `isOnDemand`, identityHash `dc4426e9d6a7b832b5461a17d983f804` синхронизирован с `scripts/build_gtfs_db.py`.
- **Ускорение первой загрузки расписания и предсобранные графы RAPTOR:**
  - `scripts/build_gtfs_db.py`: `PRAGMA page_size = 8192`, без лишних индексов, обрезка неиспользуемых `shapes` + округление координат (5 знаков) + упрощение Дугласа–Пекера (`SHAPE_SIMPLIFY_METERS = 8.0`), 21-дневное скользящее окно для ж/д сервисов (`RAIL_WINDOW_DAYS = 21`).
  - **Предсобранный бинарный бандл графов (`raptor_graphs.bundle.gz`, формат `ODRB` v1):** `scripts/build_gtfs_db.py` заранее собирает и дедуплицирует бинарные графы `RaptorData` (`ODRC` v1) на ближайшие 7 дней прямо в GitHub Actions и публикует в релиз `schedule-latest` вместе с `poznan_schedule.db.gz` и `version.json`.
  - `PrebuiltDbDownloader.kt` + `RaptorDataCache.installPrebuiltBundle(...)`: после подмены БД распаковывает готовый бандл в `cacheDir` (патча `dbFingerprint` и `serviceDate` в заголовке), поэтому `TransitGraphRepository.reloadForToday(preferDiskCache = true)` поднимает граф за **~50–120 мс** вместо 4–6 сек сборки из SQLite.
  - `OnboardingViewModel.kt` + `OnboardingScreen.kt`: офлайн-карта `poznan.pmtiles` скачивается сразу **вместе с расписанием** (параллельно при первом запуске и при нажатии «Обновить расписание», если карта ещё не загружена).
  - `PrebuiltDbDownloader.kt`: буферизованная распаковка GZIP (буферы 256 КБ / 128 КБ); `TransitGraphRepository.kt`: прямое чтение `stop_times` через `SupportSQLiteDatabase` (`loadStopTimesForServicesFast`) и асинхронное сохранение кэша при fallback.
- **Пригородные электрички (Koleje Wielkopolskie + Polregio):** `scripts/build_gtfs_db.py` автоматически объединяет GTFS ZTM Poznań с региональными поездами (`https://mkuran.pl/gtfs/polish_trains.zip`, резерв `http://ws.kolejewlkp.pl:83/gtfs_kw.zip`, агентства `KW` и `PR` в bbox Познаньской агломерации, префикс `rail_`, радиус пересадок ZTM↔ж/д до 450 м).
- **Раздельный календарь ZTM и ж/д (`TransitGraphRepository.resolveActiveServicesForDate`):** активные `service_id` для ZTM и `rail_*` определяются независимо с отдельными фолбэками (включая глобальный поиск ближайшей даты в `calendar_dates` для ж/д через `GtfsDao.getDistinctRailCalendarDates`), чтобы электрички никогда не пропадали; в статических расписаниях ж/д линии группируются по направлениям (`headsign`), а не по общему `direction_id`.
- **Защита данных с поездами при обновлении:** удалён дубликат workflow `.github/workflows/main.yml` (оставлен один `update-ztm-schedule.yml` с `concurrency`-локом), `PrebuiltDbDownloader` проверяет `room_version <= GtfsDatabase.SCHEMA_VERSION`, `MainActivity` / `GtfsSyncWorker` / `OnboardingViewModel` не затирают существующую базу ZTM+ж/д через ZTM-only `GtfsImporter` при временном сбое GitHub; `DatabaseHolder` хранит стабильный `raptor_db_fingerprint.txt` после WAL-инициализации, а `RaptorDataCache.installPrebuiltBundle` распаковывает графы потоково (~3.5 МБ пик ОЗУ).
- **Бинарный дисковый кэш графа (`RaptorDataCache.kt`):** сохраняет собранный граф в `cacheDir/raptor_graph_v1_YYYYMMDD.bin`, `OffDojadeApp.onCreate` заранее прогревает сегодня и завтра (~50–120 мс).
- **GTFS-RT (`RealtimeRepository.kt`):** настраиваемый интервал опроса (`15/20/30/60 с` или пауза/офлайн `0`), `LiveVehicle` хранит `tripId`, `routeId`, `bearing`, `currentStopId`, `currentStopSequence`, Graceful Fallback в `Offline`.
- **Пешие маршруты (`WalkingRouteProvider.kt`):** OSRM Foot API возвращает реальную геометрию и реальную дистанцию по дорогам (`routes[0].distance`), с защитой от привязки к мостам/виадукам на смене платформ и коротких переходах `≤ 120 м`.
- **Уведомления (`data/notifications/`):**
  - `OffDojadeNotifications.kt` — тихое фоновое уведомление обновления расписания в `GtfsSyncWorker` (только когда приложение не на переднем плане).
  - `TripTrackingService.kt` — Foreground-сервис (`dataSync`) с динамическим уведомлением поездки (прогресс-бар, текущая остановка по GPS/`stop_id` или расписанию, подсказки о пересадке и выходе на следующей остановке, кнопка «Остановить»).

### D. Движок маршрутизации RAPTOR (`domain/routing/RaptorEngine.kt`)
- In-Memory массивы (`RaptorData`), поиск за `1–10 мс`, поддержка произвольных дат и автоматический переход через полночь.
- Режимы:
  - **Быстрый (`FASTEST`):** минимальное прибытие, пересадки пешком до 300 м.
  - **Лучший (`BEST`):** пересадки `3–20 мин` (для трамвай → трамвай на одной остановке в городе разрешены `2 мин`), разрешены 2 пересадки вида «автобус → трамвай → трамвай» при въезде в город (`isBusIntoCityTramToTram`).
  - **Удобный (`CONVENIENT`):** минимум пересадок и пеших переходов; при выбранной остановке отправления не заставляет идти пешком на соседнюю.
- Исправлены: обрезка коротких перегонов в раунде 0 (`isTargetStop`), скачок утренних рейсов на ночные (`MAX_MODAL_ALT_DELAY_SEC = 90 мин`), недобор маршрутов после Парето-фильтрации (изначально показывается **8 маршрутов**), сохранение списка при просмотре деталей и сброс кэша при повторном поиске, запрет абсурдных поездок (переезд между платформами одного узла `stopGroupIds`, пешая часть ≥ прямой дистанции, отсутствие приближения к цели `isAbsurdJourney`), обход `CONVENIENT` через footpaths в раунде 0.

### E. UI (`ui/`) — 5 языков (`PL / EN / RU / UK / DE` в `AppStrings.kt`)
- **Иконки:** адаптивная иконка лаунчера из `E:\dojade\icon\pyricon.png` (`#0D3D27`) и внутренний логотип `drawable-nodpi/ic_pyrtransit_logo.png` из `pyriconnogreen.png`.
- **`HomeScreen`:** поиск остановок/адресов, выбор точки/остановки на оффлайн-карте (`MapLocationPickerDialog`), 3-барабанный выбор даты и времени в стиле Jakdojade (`ModalBottomSheet` с `rememberSnapFlingBehavior`), быстрые карточки и история.
- **`RouteResultsScreen`:** 8 маршрутов по умолчанию, свайпы вверх/вниз без кругового спиннера, разделители дней с учётом выбранной локали, закрываемая плашка оффлайн-режима, компактные карточки без иконок транспорта (формат `1 ч 1 мин`), пульсирующая анимация `<1` и живые значки (зелёный/красный).
- **`RouteDetailsScreen`:** оффлайн-карта `OfflineMapStyleHelper` (светлая и тёмная темы, названия улиц и районов, 18 значков университетов и мест Познани с `minZoom = 13.5`, скрытие промежуточных кружков при отдалении, строго 1 точный трамвай/автобус на сегмент), автообновление живых задержек при входе, нейтральные тёмные плашки пересадок, точный расчёт минут пересадки, компактная подпись смены платформы, предупреждение о короткой пересадке (только между двумя сегментами транспорта), кнопка динамического уведомления в TopAppBar.
- **`TimetablesScreen`:** статичные расписания линий с вкладками **«Трамваи | Автобусы | Поезда»** и переключателем **«Список | Карта»** (`TimetableStopsMapView`).
- **`LiveMapScreen` (вкладка «Карта» в нижней панели):** живая оффлайн-карта со всеми активными трамваями и автобусами из GTFS-RT, фильтром по виду транспорта и поиском по номеру линии; при нажатии на транспорт рисуется полупрозрачная линия маршрута с остановками и всплывает карточка с направлением (`→ headsign`), текущей/следующей остановкой и задержкой.
- **`SettingsScreen`:** выбор языка (5 локалей), тема, шрифт, профиль тарифа и зона, типы транспорта, режим маршрутизации, темп ходьбы (`SLOW/NORMAL/FAST`), макс. дистанция пешком (`300–1000 м`), интервал обновления Live GPS (`15/20/30/60 с` или выкл), автообновление расписания при запуске, показ значков ориентиров на карте, статус офлайн-БД и карты, кнопки обновления расписания и пересборки кэша графа.

---

## 3. Текущий статус
- Юнит-тесты: **36/36 passed** (`testDebugUnitTest`, включая кросс-языковой тест совместимости Python-сборщика и Android-загрузчика `RaptorDataCacheBundleTest`).
- Релизная сборка: подписанный `app/build/outputs/apk/release/app-release.apk` (`BUILD SUCCESSFUL`).
