# PyrTransit — Offline-First Transit Navigator for ZTM Poznań

**PyrTransit** is a fast, offline-first Android public transit navigator for **ZTM Poznań** (trams, city buses, suburban buses, and night lines across tariff zones **A, B, C, D**).

Built with **Kotlin**, **Jetpack Compose (Material 3)**, a custom in-memory **RAPTOR** routing engine, **MapLibre Native** with offline **PMTiles** vector maps, and **GTFS-RT** real-time tracking.

---

## Features

- **Instant Offline Routing (RAPTOR)**
  - In-memory array-based RAPTOR engine (~1–10 ms per query) with binary disk cache (`raptor_graph_v1_YYYYMMDD.bin`) and cold-start pre-warming for today and tomorrow.
  - Supports stop-to-stop, GPS-to-stop, address-to-address, and **interactive map location picking** directly from the home screen.
  - Flexible trip planning: **Now**, **Depart at**, **Arrive by**, and **arbitrary future date selection** (tomorrow, day after tomorrow, or calendar picker) with automatic next-day transitions and day dividers.
  - Three route profiles: **Fastest**, **Best** (3–20 min comfortable transfers), and **Convenient** (fewer transfers, avoids unnecessary walks).
  - Swipe-down / swipe-up pagination for earlier and later departures, with automatic list preservation when inspecting trip details.

- **Real-Time Tracking & Dynamic Notifications (GTFS-RT)**
  - Live `trip_updates.pb` and `vehicle_positions.pb` polling via Square Wire protobufs with graceful offline fallback and a dismissible offline notice banner.
  - Pulsing live signal indicators (green for on-schedule, red for delayed) across route lists and trip headers.
  - Exact single-trip live bus and tram markers on the map with delay & freshness popups.
  - **Dynamic Trip Tracking Notification** (`TripTrackingService`): ongoing notification with progress bar, live vehicle stop position, remaining stops/minutes, transfer instructions, and next-stop exit alerts.
  - **Background Schedule Sync Notification**: silent progress notification when WorkManager updates the offline schedule in the background.

- **Offline Vector Maps (MapLibre + PMTiles)**
  - Full Poznań & agglomeration offline map (`poznan.pmtiles`, Protomaps v4) with automatic **Light & Dark Theme** palettes.
  - Street names, district labels, and 18 major Poznań universities & landmarks shown on zoom.
  - Zoom-aware route stops (intermediate stops appear when zoomed in; start, end, and transfer stops stay visible) with tap-to-inspect stop details (zone badges `B`/`C`/`D`, on-demand `NŻ` / `ПТ` badges).
  - Interactive **stop maps inside Static Timetables** (`List | Map` toggle per route direction).

- **ZTM Poznań Tariff Calculator**
  - Automatic comparison of **PEKA tPortmonetka** (including ≤ 20-minute transfer continuation rules) vs **time-based tickets** (15 / 45 / 90 min, 24h) for both **Normalny** and **Ulgowy** fares across zones **A / B / C / D**.
  - Automatically hides ZTM pricing on non-ZTM PKS routes.

- **5-Language Interface**
  - Built-in support for **Polish (PL)**, **English (EN)**, **Russian (RU)**, **Ukrainian (UK)**, and **German (DE)**.

---

## Architecture

```text
app/src/main/java/pl/poznan/offdojade/
├── config/           # AppConfig (GitHub Releases & GTFS-RT URLs), ZtmTariffConfig
├── data/
│   ├── gtfs/         # GtfsImporter, PrebuiltDbDownloader, GeoUtils
│   ├── local/        # Room v3 GtfsDatabase, DAOs, DataStore PreferencesRepository
│   ├── location/     # GPS LocationProvider, AddressSearchProvider, WalkingRouteProvider
│   ├── map/          # Offline PMTiles MapDownloader
│   ├── notifications/# OffDojadeNotifications, TripTrackingService (dynamic notification)
│   ├── realtime/     # GtfsRtClient (Wire), RealtimeRepository
│   └── worker/       # GtfsSyncWorker (12h background schedule sync)
├── domain/
│   ├── model/        # JourneyModels, StopGroup, PlannedTime, TripSettings
│   ├── routing/      # RaptorEngine, RaptorData, RaptorDataCache, TransitGraphRepository
│   └── tariff/       # ZtmTicketCalculator
└── ui/
    ├── components/   # OfflineMapStyleHelper, MapLocationPickerDialog, TransitComponents
    ├── details/      # RouteDetailsScreen & RouteDetailsViewModel
    ├── home/         # HomeScreen & HomeViewModel
    ├── localization/ # AppStrings (PL / EN / RU / UK / DE)
    ├── onboarding/   # OnboardingScreen & OnboardingViewModel
    ├── results/      # RouteResultsScreen & RouteResultsViewModel
    ├── settings/     # SettingsScreen & SettingsViewModel
    └── timetables/   # TimetablesScreen & TimetablesViewModel
```

---

## Automated Schedule & Map Releases

GitHub Actions automatically builds and publishes pre-compiled offline assets to GitHub Releases so the app can sync in ~1 second instead of parsing raw GTFS CSV files on-device:

- Schedule metadata: `https://github.com/gomania6/pyrtransit/releases/download/schedule-latest/version.json`
- Prebuilt Room v3 SQLite DB (gzipped): `https://github.com/gomania6/pyrtransit/releases/download/schedule-latest/poznan_schedule.db.gz`
- Offline vector map (Protomaps v4 PMTiles): `https://github.com/gomania6/pyrtransit/releases/download/map-v1/poznan.pmtiles`

Workflows:
- [`.github/workflows/update-ztm-schedule.yml`](.github/workflows/update-ztm-schedule.yml) — nightly GTFS fetch & SQLite build (`scripts/build_gtfs_db.py`).
- [`.github/workflows/build-poznan-map.yml`](.github/workflows/build-poznan-map.yml) — Poznań & agglomeration PMTiles vector map extractor.

---

## Building Locally

### Requirements
- **JDK 21**
- **Android SDK** (compileSdk 35, minSdk 26)

### Commands

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build optimized release APK (R8 minification + resource shrinking)
./gradlew assembleRelease
```

The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

---

## Data Sources

- Static schedules & real-time feeds: **ZTM Poznań** Open Data (`ztm.poznan.pl`)
- Offline vector map tiles: **OpenStreetMap** / **Protomaps**
- Walking geometry & geocoding: **OSRM** / **Photon** / **Nominatim**
