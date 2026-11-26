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

    private fun buildDatabase(): GtfsDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            GtfsDatabase::class.java,
            AppConfig.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2)
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

            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Не удалось удалить старую базу ${targetFile.name}")
            }
            if (!downloadedDbFile.renameTo(targetFile)) {
                downloadedDbFile.copyTo(targetFile, overwrite = true)
                downloadedDbFile.delete()
            }

            currentDb = buildDatabase()
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
    }
}
