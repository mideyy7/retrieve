package com.mideyy7.retrieve.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history (JSON for each version in app/schemas/):
 *  v1  documents + chunks
 *  v2  chunks.ocr (auto-migration: new column with default 0)
 */
@Database(
    entities = [Document::class, Chunk::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class RetrieveDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun chunks(): ChunkDao
    abstract fun ingest(): IngestDao

    companion object {
        @Volatile private var instance: RetrieveDatabase? = null

        fun get(context: Context): RetrieveDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, RetrieveDatabase::class.java, "retrieve.db")
                    .build()
                    .also { instance = it }
            }
    }
}
