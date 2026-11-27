package pl.poznan.offdojade.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.poznan.offdojade.domain.model.AppLanguage
import pl.poznan.offdojade.domain.model.AppSettings
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.ThemeMode
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.domain.model.WalkingPace

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "offdojade_prefs")

enum class QuickDestinationSlot {
    HOME,
    CENTER,
    WORK
}

data class QuickDestination(
    val slot: QuickDestinationSlot,
    val title: String,
    val stopId: String?,
    val stopName: String?
)

data class SyncMetadata(
    val etag: String?,
    val lastModified: String?,
    val archiveName: String?,
    val lastSyncEpochMs: Long,
    val prebuiltDbSha256: String? = null,
    val prebuiltBuildDate: String? = null
)

@Serializable
data class TripHistoryEntry(
    val id: String,
    /** null = GPS */
    val fromStopId: String? = null,
    val fromName: String? = null,
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toStopId: String? = null,
    val toName: String,
    val toLat: Double = 0.0,
    val toLon: Double = 0.0,
    val timestampMs: Long = 0L
)

@Serializable
data class RecentStopEntry(
    val stopId: String,
    val displayName: String,
    val zones: List<String> = listOf("A"),
    val platformCount: Int = 1,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

class PreferencesRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_GTFS_ETAG = stringPreferencesKey("last_gtfs_etag")
        val LAST_GTFS_MODIFIED = stringPreferencesKey("last_gtfs_modified")
        val LAST_GTFS_ARCHIVE_NAME = stringPreferencesKey("last_gtfs_archive_name")
        val LAST_GTFS_SYNC_MS = longPreferencesKey("last_gtfs_sync_ms")
        val LAST_PREBUILT_SHA256 = stringPreferencesKey("last_prebuilt_sha256")
        val LAST_PREBUILT_BUILD_DATE = stringPreferencesKey("last_prebuilt_build_date")

        val HOME_STOP_ID = stringPreferencesKey("home_stop_id")
        val HOME_STOP_NAME = stringPreferencesKey("home_stop_name")
        val CENTER_STOP_ID = stringPreferencesKey("center_stop_id")
        val CENTER_STOP_NAME = stringPreferencesKey("center_stop_name")
        val WORK_STOP_ID = stringPreferencesKey("work_stop_id")
        val WORK_STOP_NAME = stringPreferencesKey("work_stop_name")

        val FAVORITE_STOP_IDS = stringSetPreferencesKey("favorite_stop_ids")

        // App & Trip Settings
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ROUTE_PREFERENCE = stringPreferencesKey("route_preference")
        val AVOID_TRANSFERS = booleanPreferencesKey("avoid_transfers")
        val ENABLED_TRANSPORT_MODES = stringSetPreferencesKey("enabled_transport_modes")
        val EXCLUDE_BUSES = booleanPreferencesKey("exclude_buses")
        val EXCLUDED_LINES = stringSetPreferencesKey("excluded_lines")
        val PREFERRED_LINES = stringSetPreferencesKey("preferred_lines")
        val EXCLUDED_ZONES = stringSetPreferencesKey("excluded_zones")
        val LOW_FLOOR_ONLY = booleanPreferencesKey("low_floor_only")
        val MIN_TRANSFER_MINUTES = intPreferencesKey("min_transfer_minutes")
        val IS_ULGOWY_TICKET = booleanPreferencesKey("is_ulgowy_ticket")
        val WALKING_PACE = stringPreferencesKey("walking_pace")
        val MAX_ORIGIN_WALK_METERS = intPreferencesKey("max_origin_walk_meters")
        val REALTIME_POLL_INTERVAL_SEC = intPreferencesKey("realtime_poll_interval_sec")
        val AUTO_UPDATE_SCHEDULE = booleanPreferencesKey("auto_update_schedule")
        val SHOW_MAP_LANDMARKS = booleanPreferencesKey("show_map_landmarks")

        // History
        val TRIP_HISTORY_JSON = stringPreferencesKey("trip_history_json")
        val RECENT_STOPS_JSON = stringPreferencesKey("recent_stops_json")
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val syncMetadata: Flow<SyncMetadata> = context.dataStore.data.map { prefs ->
        SyncMetadata(
            etag = prefs[Keys.LAST_GTFS_ETAG],
            lastModified = prefs[Keys.LAST_GTFS_MODIFIED],
            archiveName = prefs[Keys.LAST_GTFS_ARCHIVE_NAME],
            lastSyncEpochMs = prefs[Keys.LAST_GTFS_SYNC_MS] ?: 0L,
            prebuiltDbSha256 = prefs[Keys.LAST_PREBUILT_SHA256],
            prebuiltBuildDate = prefs[Keys.LAST_PREBUILT_BUILD_DATE]
        )
    }

    val quickDestinations: Flow<List<QuickDestination>> = context.dataStore.data.map { prefs ->
        listOf(
            QuickDestination(
                slot = QuickDestinationSlot.HOME,
                title = "Домой",
                stopId = prefs[Keys.HOME_STOP_ID],
                stopName = prefs[Keys.HOME_STOP_NAME]
            ),
            QuickDestination(
                slot = QuickDestinationSlot.CENTER,
                title = "В центр",
                stopId = prefs[Keys.CENTER_STOP_ID],
                stopName = prefs[Keys.CENTER_STOP_NAME] ?: "Rondo Kaponiera"
            ),
            QuickDestination(
                slot = QuickDestinationSlot.WORK,
                title = "На работу",
                stopId = prefs[Keys.WORK_STOP_ID],
                stopName = prefs[Keys.WORK_STOP_NAME]
            )
        )
    }

    val favoriteStopIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.FAVORITE_STOP_IDS] ?: emptySet()
    }

    val tripHistory: Flow<List<TripHistoryEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.TRIP_HISTORY_JSON] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<TripHistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    val recentStops: Flow<List<RecentStopEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_STOPS_JSON] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<RecentStopEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun addTripHistory(entry: TripHistoryEntry) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.TRIP_HISTORY_JSON]
                ?.let { runCatching { json.decodeFromString<List<TripHistoryEntry>>(it) }.getOrNull() }
                .orEmpty()
            val updated = (listOf(entry) + current.filterNot { it.id == entry.id }).take(10)
            prefs[Keys.TRIP_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun removeTripHistory(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.TRIP_HISTORY_JSON]
                ?.let { runCatching { json.decodeFromString<List<TripHistoryEntry>>(it) }.getOrNull() }
                .orEmpty()
            val updated = current.filterNot { it.id == id }
            prefs[Keys.TRIP_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearTripHistory() {
        context.dataStore.edit { it.remove(Keys.TRIP_HISTORY_JSON) }
    }

    suspend fun addRecentStop(entry: RecentStopEntry) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_STOPS_JSON]
                ?.let { runCatching { json.decodeFromString<List<RecentStopEntry>>(it) }.getOrNull() }
                .orEmpty()
            val updated = (listOf(entry) + current.filterNot {
                it.displayName.equals(entry.displayName, ignoreCase = true)
            }).take(10)
            prefs[Keys.RECENT_STOPS_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearRecentStops() {
        context.dataStore.edit { it.remove(Keys.RECENT_STOPS_JSON) }
    }

    val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val rawModes = prefs[Keys.ENABLED_TRANSPORT_MODES]
        val enabledModes = if (rawModes == null) {
            TransportMode.ALL
        } else {
            rawModes.mapNotNull { TransportMode.fromKey(it) }.toSet()
        }
        AppSettings(
            language = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            themeMode = ThemeMode.fromKey(prefs[Keys.THEME_MODE]),
            tripSettings = TripSettings(
                routePreference = RoutePreference.fromKey(prefs[Keys.ROUTE_PREFERENCE]),
                avoidTransfers = prefs[Keys.AVOID_TRANSFERS] ?: false,
                enabledTransportModes = enabledModes,
                excludeBuses = prefs[Keys.EXCLUDE_BUSES] ?: false,
                excludedLines = prefs[Keys.EXCLUDED_LINES] ?: emptySet(),
                preferredLines = prefs[Keys.PREFERRED_LINES] ?: emptySet(),
                excludedZones = prefs[Keys.EXCLUDED_ZONES] ?: emptySet(),
                lowFloorOnly = prefs[Keys.LOW_FLOOR_ONLY] ?: false,
                minTransferMinutes = prefs[Keys.MIN_TRANSFER_MINUTES] ?: 2,
                isUlgowyTicket = prefs[Keys.IS_ULGOWY_TICKET] ?: false,
                walkingPace = WalkingPace.fromKey(prefs[Keys.WALKING_PACE]),
                maxOriginWalkMeters = (prefs[Keys.MAX_ORIGIN_WALK_METERS] ?: 600).coerceIn(300, 1000)
            ),
            realtimePollIntervalSec = prefs[Keys.REALTIME_POLL_INTERVAL_SEC] ?: 20,
            autoUpdateSchedule = prefs[Keys.AUTO_UPDATE_SCHEDULE] ?: true,
            showMapLandmarks = prefs[Keys.SHOW_MAP_LANDMARKS] ?: true
        )
    }

    suspend fun getAppSettings(): AppSettings = appSettings.first()

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.key }
    }

    suspend fun setRoutePreference(pref: RoutePreference) {
        context.dataStore.edit { it[Keys.ROUTE_PREFERENCE] = pref.key }
    }

    suspend fun setAvoidTransfers(avoid: Boolean) {
        context.dataStore.edit { it[Keys.AVOID_TRANSFERS] = avoid }
    }

    suspend fun toggleTransportMode(mode: TransportMode) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.ENABLED_TRANSPORT_MODES]
                ?.mapNotNull { TransportMode.fromKey(it) }
                ?.toMutableSet()
                ?: TransportMode.ALL.toMutableSet()
            if (!current.add(mode)) {
                if (current.size > 1) {
                    current.remove(mode)
                }
            }
            prefs[Keys.ENABLED_TRANSPORT_MODES] = current.map { it.key }.toSet()
            if (mode == TransportMode.BUS) {
                prefs[Keys.EXCLUDE_BUSES] = TransportMode.BUS !in current
            }
        }
    }

    suspend fun setExcludeBuses(exclude: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EXCLUDE_BUSES] = exclude
            val current = prefs[Keys.ENABLED_TRANSPORT_MODES]
                ?.mapNotNull { TransportMode.fromKey(it) }
                ?.toMutableSet()
                ?: TransportMode.ALL.toMutableSet()
            if (exclude) {
                if (current.size > 1) current.remove(TransportMode.BUS)
            } else {
                current.add(TransportMode.BUS)
            }
            prefs[Keys.ENABLED_TRANSPORT_MODES] = current.map { it.key }.toSet()
        }
    }

    suspend fun addExcludedLine(line: String) {
        val clean = line.trim().uppercase()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.EXCLUDED_LINES] ?: emptySet()).toMutableSet()
            current.add(clean)
            prefs[Keys.EXCLUDED_LINES] = current
            // Убираем из предпочитаемых, если была там
            val preferred = (prefs[Keys.PREFERRED_LINES] ?: emptySet()).toMutableSet()
            if (preferred.remove(clean)) {
                prefs[Keys.PREFERRED_LINES] = preferred
            }
        }
    }

    suspend fun removeExcludedLine(line: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.EXCLUDED_LINES] ?: emptySet()).toMutableSet()
            current.remove(line)
            prefs[Keys.EXCLUDED_LINES] = current
        }
    }

    suspend fun addPreferredLine(line: String) {
        val clean = line.trim().uppercase()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.PREFERRED_LINES] ?: emptySet()).toMutableSet()
            current.add(clean)
            prefs[Keys.PREFERRED_LINES] = current
            // Убираем из исключённых, если была там
            val excluded = (prefs[Keys.EXCLUDED_LINES] ?: emptySet()).toMutableSet()
            if (excluded.remove(clean)) {
                prefs[Keys.EXCLUDED_LINES] = excluded
            }
        }
    }

    suspend fun removePreferredLine(line: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.PREFERRED_LINES] ?: emptySet()).toMutableSet()
            current.remove(line)
            prefs[Keys.PREFERRED_LINES] = current
        }
    }

    suspend fun toggleExcludedZone(zone: String) {
        val clean = zone.trim().uppercase()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.EXCLUDED_ZONES] ?: emptySet()).toMutableSet()
            if (!current.add(clean)) {
                current.remove(clean)
            }
            prefs[Keys.EXCLUDED_ZONES] = current
        }
    }

    suspend fun setLowFloorOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOW_FLOOR_ONLY] = enabled }
    }

    suspend fun setMinTransferMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.MIN_TRANSFER_MINUTES] = minutes.coerceIn(0, 15) }
    }

    suspend fun setUlgowyTicket(isUlgowy: Boolean) {
        context.dataStore.edit { it[Keys.IS_ULGOWY_TICKET] = isUlgowy }
    }

    suspend fun setWalkingPace(pace: WalkingPace) {
        context.dataStore.edit { it[Keys.WALKING_PACE] = pace.key }
    }

    suspend fun setMaxOriginWalkMeters(meters: Int) {
        context.dataStore.edit { it[Keys.MAX_ORIGIN_WALK_METERS] = meters.coerceIn(300, 1000) }
    }

    suspend fun setRealtimePollIntervalSec(seconds: Int) {
        context.dataStore.edit { it[Keys.REALTIME_POLL_INTERVAL_SEC] = seconds.coerceIn(0, 120) }
    }

    suspend fun setAutoUpdateSchedule(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_SCHEDULE] = enabled }
    }

    suspend fun setShowMapLandmarks(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_MAP_LANDMARKS] = show }
    }

    suspend fun resetTripSettings() {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROUTE_PREFERENCE] = RoutePreference.BEST.key
            prefs[Keys.AVOID_TRANSFERS] = false
            prefs[Keys.ENABLED_TRANSPORT_MODES] = TransportMode.ALL.map { it.key }.toSet()
            prefs[Keys.EXCLUDE_BUSES] = false
            prefs[Keys.EXCLUDED_LINES] = emptySet()
            prefs[Keys.PREFERRED_LINES] = emptySet()
            prefs[Keys.EXCLUDED_ZONES] = emptySet()
            prefs[Keys.LOW_FLOOR_ONLY] = false
            prefs[Keys.MIN_TRANSFER_MINUTES] = 2
            prefs[Keys.WALKING_PACE] = WalkingPace.NORMAL.key
            prefs[Keys.MAX_ORIGIN_WALK_METERS] = 600
        }
    }

    suspend fun getSyncMetadata(): SyncMetadata = syncMetadata.first()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun updateSyncMetadata(
        etag: String?,
        lastModified: String?,
        archiveName: String?,
        syncEpochMs: Long = System.currentTimeMillis()
    ) {
        context.dataStore.edit { prefs ->
            if (etag != null) prefs[Keys.LAST_GTFS_ETAG] = etag
            if (lastModified != null) prefs[Keys.LAST_GTFS_MODIFIED] = lastModified
            if (archiveName != null) prefs[Keys.LAST_GTFS_ARCHIVE_NAME] = archiveName
            prefs[Keys.LAST_GTFS_SYNC_MS] = syncEpochMs
        }
    }

    suspend fun updatePrebuiltDbMetadata(
        sha256: String,
        buildDate: String,
        syncEpochMs: Long = System.currentTimeMillis()
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_PREBUILT_SHA256] = sha256
            prefs[Keys.LAST_PREBUILT_BUILD_DATE] = buildDate
            prefs[Keys.LAST_GTFS_SYNC_MS] = syncEpochMs
        }
    }

    suspend fun setQuickDestination(slot: QuickDestinationSlot, stopId: String, stopName: String) {
        context.dataStore.edit { prefs ->
            when (slot) {
                QuickDestinationSlot.HOME -> {
                    prefs[Keys.HOME_STOP_ID] = stopId
                    prefs[Keys.HOME_STOP_NAME] = stopName
                }
                QuickDestinationSlot.CENTER -> {
                    prefs[Keys.CENTER_STOP_ID] = stopId
                    prefs[Keys.CENTER_STOP_NAME] = stopName
                }
                QuickDestinationSlot.WORK -> {
                    prefs[Keys.WORK_STOP_ID] = stopId
                    prefs[Keys.WORK_STOP_NAME] = stopName
                }
            }
        }
    }

    suspend fun toggleFavoriteStop(stopId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITE_STOP_IDS]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(stopId)) {
                current.remove(stopId)
            }
            prefs[Keys.FAVORITE_STOP_IDS] = current
        }
    }
}
