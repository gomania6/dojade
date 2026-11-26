# AGENT.md — OffDojade (ZTM Poznań Offline-First Navigator)

Контекстный файл для продолжения работы над проектом в новом чате (`e:\dojade`).

## 1. Обзор проекта
Нативное Android-приложение на **Kotlin 2.0.21 + Jetpack Compose (Material 3)** — сверхбыстрый офлайн-навигатор общественного транспорта для Познани и агломерации (**ZTM Poznań**) без рекламы и собственного бэкенд-сервера.

- **Package / Application ID:** `pl.poznan.offdojade`
- **SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- **Сборка и среда (ВАЖНО):**
  - На машине по умолчанию в `PATH` стоит Java 25, но **Gradle 8.9 + AGP 8.7.3 требуют JDK 21**.
  - Всегда запускайте Gradle с:
    ```powershell
    $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
    .\gradlew.bat testDebugUnitTest assembleDebug
    ```
  - Android SDK: `C:\Users\goman\AppData\Local\Android\Sdk` (прописан в `local.properties`).
  - Стек: Koin 4.0.0 (DI), Room 2.6.1 (KSP), DataStore Preferences, OkHttp 4.12, Square Wire 5.1.0 (`gtfs-realtime.proto`), MapLibre Native Android 11.6.1 (`pmtiles://file://...`), WorkManager, Play Services Location.

---

## 2. Что уже реализовано и проверено

### A. Конфигурация и тарифы (`config/`)
- `app/src/main/java/pl/poznan/offdojade/config/AppConfig.kt`:
  - `ZTM_GTFS_DIRECT_ZIP_URL = "https://www.ztm.poznan.pl/pl/dla-deweloperow/getGTFSFile"`
  - `ZTM_GTFS_RT_TRIP_UPDATES_URL` и `ZTM_GTFS_RT_VEHICLE_POSITIONS_URL`
  - `MAP_DOWNLOAD_URL` (строки 41–42) — ссылка на `poznan.pmtiles` в GitHub Releases (сейчас плейсхолдер `USER/REPO`)
  - `SCHEDULE_VERSION_URL` и `SCHEDULE_DB_GZ_URL` (строки 52–61) — ссылки на `version.json` и `poznan_schedule.db.gz` из GitHub Actions (сейчас плейсхолдер `USER/REPO`)
- `app/src/main/java/pl/poznan/offdojade/config/ZtmTariffConfig.kt`:
  - Временные билеты ZTM: 15 мин (4 zł), 45 мин (6 zł), 90 мин (8 zł), 24 ч (15 zł) + буфер +5 мин.
  - Дегрессивная шкала PEKA tPortmonetka (0.72 → 0.60 → 0.42 → 0.30 → 0.24 → 0.12 zł/ост.).

### B. Сетевая безопасность TLS для ZTM Poznań (исправлено!)
- Сервер `www.ztm.poznan.pl` использует сертификат УЦ **Certum (Asseco Data Systems)** (`Certum OV TLS G2 R39 CA` → `Certum Trusted Root CA`), который отсутствует в стандартном хранилище Android (`CertPathValidatorException`).
- Экспортирована проверенная цепочка в `app/src/main/res/raw/ztm_certum_ca.pem` и подключена через `app/src/main/res/xml/network_security_config.xml` (строго для домена `ztm.poznan.pl`) в `AndroidManifest.xml`.

### C. Слой данных (`data/`) и «Бесплатный сервер» на GitHub Actions
- **Room + безопасная подмена файла БД:** `GtfsDatabase.kt` (`version = 1`, `identityHash = 26172cec1c00d49b87e1ddbeb00c4118`), `GtfsEntities.kt`, `GtfsDao.kt`, `DatabaseHolder.kt` (закрывает Room, удаляет `-wal/-shm`, атомарно подменяет файл БД и переоткрывает Room).
- **Потоковый импортер GTFS ZIP:** `GtfsImporter.kt` + `CsvLineParser.kt` + `ServiceCalendarResolver.kt` + `FootpathGenerator.kt` (генерация пеших пересадок `< 300 м` со скоростью `1.2 м/с` по формуле Хаверсина через spatial grid).
- **Быстрое обновление из GitHub Releases:** `PrebuiltDbDownloader.kt` проверяет `version.json` (~100 байт), сверяет SHA-256, скачивает и распаковывает `poznan_schedule.db.gz` через `GZIPInputStream` и подменяет локальную БД через `DatabaseHolder`. Если `USER/REPO` не настроен — автоматический fallback на прямой импорт с ZTM Poznań.
- **GitHub Actions пайплайн:**
  - `scripts/build_gtfs_db.py` (чистый Python 3 stdlib) — скачивает `gtfs.zip`, создает Room-совместимую SQLite БД (с теми же индексами и `room_master_table`), строит пешие переходы `< 300 м`, делает `VACUUM`, сжимает в `poznan_schedule.db.gz` и пишет `version.json`.
  - `.github/workflows/update-ztm-schedule.yml` — запускается каждый день в 03:00 по Познани (`0 2 * * *` UTC) и вручную (`workflow_dispatch`), заливает артефакты в релиз `schedule-latest`.
- **GTFS-RT:** `GtfsRtClient.kt` + `RealtimeRepository.kt` — опрос каждые 20 с в `Lifecycle.State.STARTED`, задержки в `ConcurrentHashMap<String, Int>`, Graceful Fallback в статус `Offline`.
- **Офлайн-карта:** `MapDownloader.kt` — стрим OkHttp в `.tmp` с прогрессом в `%`/`МБ` и атомарным переименованием в `filesDir/poznan.pmtiles`.
- **Фоновое обновление:** `GtfsSyncWorker.kt` (`PeriodicWorkRequest` 24 ч, `NetworkType.UNMETERED`).

### D. Движок маршрутизации RAPTOR и калькулятор билетов (`domain/`)
- `RaptorData.kt` + `TransitGraphBuilder.kt` + `TransitGraphRepository.kt` + `RaptorEngine.kt`:
  - Компактные In-Memory структуры на `IntArray` (без SQL в цикле поиска, `< 50 мс`).
  - **Объединение одинаковых по названию остановок:** `StopGroup` (в поиске, быстрых карточках и ближайших остановках) + автоматическое раскрытие всех платформ с одинаковым названием (`findSiblingStopIndices`) для старта и финиша в RAPTOR.
  - Поиск как от **GPS (до 600 м)**, так и **Пункт А → Пункт Б** (до 3 пересадок), учет живых задержек (`effective_time = scheduled_time + delay_seconds`), индикатор «Успею ли добежать» (`CatchabilityInfo`).
- `ZtmTicketCalculator.kt` — расчет временных билетов с буфером +5 мин и задержками (предупреждение о билете «впритык»), PEKA tPortmonetka, сравнение выгоды, определение тарифных зон (`A + B`).

### E. UI (`ui/`) — 4 экрана на Compose + Material 3
1. `OnboardingScreen` — прогресс-бары скачивания `poznan.pmtiles` (с кнопкой «Пропустить пока») и синхронизации расписания ZTM (GitHub Releases → fallback ZTM).
2. `HomeScreen` — статус `Live • ZTM`/`Offline`, выбор **Пункт А (Откуда / GPS) → Пункт Б (Куда)** с кнопкой Swap, 3 быстрые карточки («Домой», «В центр», «На работу» с настройкой по долгому нажатию), объединённые карточки ближайших остановок с живым табло.
3. `RouteResultsScreen` — список маршрутов `Пункт А → Пункт Б`, бейджи трамваев/автобусов, «Успею ли добежать», калькулятор билетов.
4. `RouteDetailsScreen` — MapLibre с локальным `poznan.pmtiles` (или резервным стилем), полилинии из `shapes.txt`, точки остановок, живые маркеры из `vehicle_positions.pb` + пошаговый таймлайн.

---

## 3. Текущий статус тестов и сборки
- Последняя проверка: `./gradlew testDebugUnitTest assembleDebug` → **`BUILD SUCCESSFUL`**.
- Unit-тесты: **12/12 passed** (`RaptorEngineTest` 5/5, `ZtmTicketCalculatorTest` 5/5, `ServiceCalendarResolverAndCsvTest` 2/2).
- `scripts/build_gtfs_db.py`: синтаксис проверен (`py_compile` OK).
- `.gitignore` создан и исключает `build/`, `.gradle/`, `local.properties`, `.idea/`, `dist/`.

## 4. Что осталось сделать пользователю / в следующем чате
1. Запушить `e:\dojade` в GitHub-репозиторий и запустить workflow **Update ZTM Poznań Schedule DB** во вкладке Actions.
2. Заменить `USER/REPO` в `AppConfig.kt` (`MAP_DOWNLOAD_URL`, `SCHEDULE_VERSION_URL`, `SCHEDULE_DB_GZ_URL`) на реальный репозиторий и пересобрать APK.
3. (Опционально) Сгенерировать и загрузить `poznan.pmtiles` в GitHub Release `map-v1`.
