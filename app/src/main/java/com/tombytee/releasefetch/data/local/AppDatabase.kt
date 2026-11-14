package com.tombytee.releasefetch.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SavedRepoEntity::class, CachedRepoPageEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedRepoDao(): SavedRepoDao
    abstract fun repoCacheDao(): RepoCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_repo_pages` (
                        `repoKey` TEXT NOT NULL,
                        `canonicalFullName` TEXT NOT NULL,
                        `ownerAvatarUrl` TEXT,
                        `etag` TEXT,
                        `releasesJson` TEXT NOT NULL,
                        `hasMoreReleases` INTEGER NOT NULL,
                        `cachedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`repoKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "releasefetch_db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
