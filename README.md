# OffDojade — Offline Transit Navigator for ZTM Poznań

**OffDojade** is an offline-first Android public transit navigator for **ZTM Poznań** (trams, city buses, suburban buses, and night lines across tariff zones **A, B, C, D**).  
Built with Kotlin, Jetpack Compose (Material 3 AMOLED UI), Room, MapLibre Native, and a custom in-memory **RAPTOR** routing engine.

---

## Features

- **Ultra-fast offline routing (RAPTOR)**
  - Runs completely offline in **1–5 ms** on compact primitive-array graphs with a binary disk cache (`RaptorDataCache`).
  - Multi-criteria trip profiles: **Convenient** (fewest transfers, minimal cross-stop walking), **Best** (balanced), and **Fastest** (shortest arrival, walking transfers ≤ 300 m).
  - Smart late-evening / night mode (21:00–05:00) that maximizes route coverage when service is sparse, plus automatic next-day rollover when today's departures end.
  - Full date & time planning (**Depart at** / **Arrive by**, Today / Tomorrow / Day after tomorrow / Calendar picker).
- **Live GTFS-RT & Interactive Offline Map**
  - Real-time delays, live countdowns, and animated live-signal indicators from ZTM Poznań GTFS-RT feeds (`trip_updates.pb`, `vehicle_positions.pb`).
  - Vector offline map powered by **MapLibre Native + PMTiles** (`poznan.pmtiles`) with color-coded transit legs, white styling for night buses, road-following pedestrian paths (OSRM with offline fallback), tappable route stops, and live vehicle popups showing only vehicles on the active route.
- **ZTM Poznań Smart Ticket Calculator**
  - Compares time tickets (15 / 45 / 90 min / 24h) with **PEKA tPortmonetka** degressive per-stop pricing.
  - Supports **Normalny** and **Ulgowy (50%)** tariffs, the **18.00 zł / 9.00 zł** daily cap, and the **20-minute transfer continuation rule** (up to 3 continuous transfers).
  - Displays Jakdojade-style circular badges for tariff zones **B / C / D** and on-demand stops (**ПТ / NŻ / ПВ / REQ / BH** from GTFS `pickup_type = 3` / `drop_off_type = 3`).
- **Search, Timetables & Quick Routes**
  - Polish diacritic-folding fuzzy stop search, street/house-number/POI **address search** (Photon / Nominatim / Android Geocoder), trip history, and recent stops.
  - One-tap **Quick Routes** (Home / Center / Work) using fresh GPS geolocation to find the nearest departure stop.
  - Static timetables browser with active-day filtering and compact line grid.
- **Automatic Offline Sync**
  - Background WorkManager sync every 12 hours + startup version check against prebuilt SQLite schedules on GitHub Releases (with direct ZTM GTFS ZIP fallback).
- **5 Languages & AMOLED Theme**
  - Full localization: **Russian (RU)**, **Ukrainian (UK)**, **Polish (PL)**, **English (EN)**, and **German (DE)**.
  - Pitch-black AMOLED dark theme, light theme, and system-default theme.

---

## Tech Stack

- **Language & UI:** Kotlin 2.0, Jetpack Compose, Material 3, Navigation Compose
- **Routing & Data:** Custom RAPTOR engine, Room (SQLite), DataStore Preferences, binary `RaptorDataCache`
- **Maps & Location:** MapLibre Native Android (`PMTiles`), Google Play Services Location, OSRM Foot Routing, Photon / Nominatim Geocoding
- **Networking & Protobuf:** OkHttp, Square Wire (GTFS-RT), Kotlinx Serialization
- **DI & Background Work:** Koin, AndroidX WorkManager

---

## Architecture

```text
app/src/main/java/pl/poznan/offdojade/
├── config/           # AppConfig (GitHub Releases & ZTM URLs), ZtmTariffConfig
├── data/
│   ├── gtfs/         # GtfsImporter, PrebuiltDbDownloader, ServiceCalendarResolver
│   ├── local/        # Room GtfsDatabase (v3), DatabaseHolder, PreferencesRepository
│   ├── location/     # LocationProvider, AddressSearchProvider, WalkingRouteProvider
│   ├── map/          # MapDownloader (PMTiles)
│   ├── realtime/     # GtfsRtClient, RealtimeRepository
│   └── worker/       # GtfsSyncWorker (12h background sync)
├── domain/
│   ├── model/        # TransitJourney, TripSettings, PlannedTime, AppSettings
│   ├── routing/      # RaptorEngine, RaptorData, RaptorDataCache, TransitGraphBuilder, TransitGraphRepository
│   └── tariff/       # ZtmTicketCalculator
└── ui/
    ├── components/   # LineBadge, AnimatedLiveSignal, TicketRecommendationCard
    ├── details/      # RouteDetailsScreen (MapLibre + collapsible timeline)
    ├── home/         # HomeScreen (search, quick routes, history, date/time picker)
    ├── localization/ # AppStrings (RU / UK / PL / EN / DE)
    ├── onboarding/   # Initial schedule & offline map download
    ├── results/      # RouteResultsScreen (upcoming / earlier / later / next day)
    ├── settings/     # Trip preferences, theme, language, sync controls
    └── timetables/   # Static lines & stop departures browser
```

---

## Building from Source

### Requirements
- **JDK 21** (Gradle 8.9 + AGP 8.7.3)
- Android SDK 35 (`minSdk = 26`, `targetSdk = 35`)

### Commands

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build minified & resource-shrunk release APK (~24 MB)
./gradlew assembleRelease
```

On Windows PowerShell (if another JDK is default in `PATH`):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
.\gradlew.bat testDebugUnitTest assembleRelease
```

Release APK output: `app/build/outputs/apk/release/app-release.apk`.

---

## Prebuilt Offline Schedule & Map Pipeline

To avoid heavy on-device GTFS parsing, schedules can be prebuilt into a Room-compatible SQLite database using [`scripts/build_gtfs_db.py`](scripts/build_gtfs_db.py) and published to GitHub Releases (`schedule-latest`):

- `https://github.com/gomania6/offdojade/releases/download/schedule-latest/version.json`
- `https://github.com/gomania6/offdojade/releases/download/schedule-latest/poznan_schedule.db.gz`
- `https://github.com/gomania6/offdojade/releases/download/map-v1/poznan.pmtiles`

```bash
python3 scripts/build_gtfs_db.py --output-dir dist
```

---

## Data Sources

- Static schedules & real-time feeds: [ZTM Poznań Open Data (GTFS / GTFS-RT)](https://www.ztm.poznan.pl/pl/dla-deweloperow/)
- Map data, geocoding & pedestrian routing: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (PMTiles / Photon / OSRM)
