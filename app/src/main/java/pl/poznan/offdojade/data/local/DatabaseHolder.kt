package pl.poznan.offdojade.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.dao.GtfsDao
import java.io.File
import java.io.IOException

/**
 * Потокобезопасный контейнер для [GtfsDatabase], позволяющий безопасно
 * подменять локальный файл SQLite-базы на готовый `.db` из GitHub Releases
 * без перезапуска приложения.
 */
class DatabaseHolder(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var currentDb: GtfsDatabase = buildDatabase()

    val database: GtfsDatabase
        get() = currentDb

    val dao: GtfsDao
        get() = currentDb.gtfsDao()

    val graphCacheFile: File
        get() = File(context.cacheDir, "raptor_graph_v1.bin")

    fun graphCacheFileForDate(dateYyyymmdd: Int): File =
        File(context.cacheDir, "raptor_graph_v1_$dateYyyymmdd.bin")

    fun tempGtfsZipFile(): File =
        File(context.cacheDir, "ztm_gtfs_download.zip.tmp")

    private val fingerprintFile: File
        get() = File(context.cacheDir, "raptor_db_fingerprint.txt")

    fun clearGraphCaches() {
        context.cacheDir.listFiles { f -> f.name.startsWith("raptor_graph_v1") }
            ?.forEach { runCatching { it.delete() } }
        runCatching { fingerprintFile.delete() }
    }

    fun pruneOldGraphCaches(keepDateYyyymmdd: Int) {
        context.cacheDir.listFiles { f -> f.name.startsWith("raptor_graph_v1_") && f.name.endsWith(".bin") }
            ?.forEach { file ->
                val datePart = file.name.removePrefix("raptor_graph_v1_").removeSuffix(".bin").toIntOrNull()
                if (datePart != null && datePart < keepDateYyyymmdd) {
                    runCatching { file.delete() }
                }
            }
    }

    /**
     * Возвращает стабильный fingerprint текущей базы SQLite, не зависящий от изменений `lastModified()`
     * при переходе SQLite в WAL-режим или checkpoint'ах Room. Сбрасывается при каждом обновлении БД.
     */
    fun getDatabaseFingerprint(): Long {
        val dbFile = context.getDatabasePath(AppConfig.DATABASE_NAME)
        if (!dbFile.exists()) return 0L
        runCatching {
            if (fingerprintFile.exists()) {
                val saved = fingerprintFile.readText().trim().toLongOrNull()
                if (saved != null && saved != 0L) return saved
            }
        }
        val computed = (dbFile.length() * 31L + dbFile.lastModified()).let { if (it == 0L) 1L else it }
        runCatching {
            fingerprintFile.parentFile?.mkdirs()
            fingerprintFile.writeText(computed.toString())
        }
        return computed
    }

    private fun writeStableFingerprint(dbFile: File): Long {
        val value = (dbFile.length() * 31L + System.currentTimeMillis()).let { if (it == 0L) 1L else it }
        runCatching {
            fingerprintFile.parentFile?.mkdirs()
            fingerprintFile.writeText(value.toString())
        }
        return value
    }

    private fun buildDatabase(): GtfsDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            GtfsDatabase::class.java,
            AppConfig.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    /**
     * Закрывает текущую базу Room, удаляет WAL/SHM-журналы, атомарно подменяет
     * файл базы на [downloadedDbFile] и открывает новый экземпляр Room.
     */
    fun replaceDatabaseFile(downloadedDbFile: File) {
        synchronized(lock) {
            currentDb.close()

            val targetFile = context.getDatabasePath(AppConfig.DATABASE_NAME)
            targetFile.parentFile?.mkdirs()

            File("${targetFile.absolutePath}-wal").delete()
            File("${targetFile.absolutePath}-shm").delete()
            File("${targetFile.absolutePath}-journal").delete()
            clearGraphCaches()

            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Не удалось удалить старую базу ${targetFile.name}")
            }
            if (!downloadedDbFile.renameTo(targetFile)) {
                downloadedDbFile.copyTo(targetFile, overwrite = true)
                downloadedDbFile.delete()
            }

            currentDb = buildDatabase()
            // Сразу открываем базу (инициализируем WAL-заголовок) и фиксируем стабильный fingerprint,
            // чтобы предсобранный бандл RAPTOR не инвалидировался на первом же SQL-запросе
            runCatching { currentDb.openHelper.readableDatabase }
            writeStableFingerprint(targetFile)
        }
    }

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `trips` ADD COLUMN `wheelchair_accessible` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `stop_times` ADD COLUMN `is_on_demand` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_stop_times_stop_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_stop_times_trip_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_shapes_shape_id`")
                db.execSQL("DROP INDEX IF EXISTS `index_transfers_from_stop_id`")
            }
        }
    }
}
