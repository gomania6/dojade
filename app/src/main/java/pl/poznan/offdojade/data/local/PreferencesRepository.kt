package pl.poznan.offdojade.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

class PreferencesRepository(private val context: Context) {

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
