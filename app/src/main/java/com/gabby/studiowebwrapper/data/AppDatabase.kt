package com.gabby.studiowebwrapper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Report::class, HistoryEntry::class, FeedbackEntry::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao
    abstract fun historyDao(): HistoryDao
    abstract fun feedbackDao(): FeedbackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app.db"
                ).addMigrations(MIGRATION_4_5).build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `feedback_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `resultJson` TEXT NOT NULL,
                        `previewUri` TEXT NOT NULL,
                        `selection` TEXT NOT NULL,
                        `comment` TEXT,
                        `modelConfidence` INTEGER NOT NULL,
                        `routedTo` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
