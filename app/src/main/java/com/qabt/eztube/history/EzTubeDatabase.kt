package com.qabt.eztube.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntry::class, FavoriteEntry::class], version = 4, exportSchema = false)
abstract class EzTubeDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var instance: EzTubeDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS favorites (
                        mediaId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        channel TEXT NOT NULL,
                        thumbnailUrl TEXT,
                        addedAt INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE listening_history ADD COLUMN channelUrl TEXT")
                db.execSQL("ALTER TABLE favorites ADD COLUMN channelUrl TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE listening_history ADD COLUMN positionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_history ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_history ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE listening_history ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("UPDATE listening_history SET updatedAt = playedAt WHERE updatedAt = 0")
                db.execSQL("ALTER TABLE favorites ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE favorites ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("UPDATE favorites SET updatedAt = addedAt WHERE updatedAt = 0")
            }
        }

        fun get(context: Context): EzTubeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EzTubeDatabase::class.java,
                    "eztube.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
