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
 *  v3  modules (code -> title) and an index on documents(module, week) (auto-migration)
 */
@Database(
    entities = [Document::class, Chunk::class, ModuleInfo::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class RetrieveDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun chunks(): ChunkDao
    abstract fun ingest(): IngestDao
    abstract fun modules(): ModuleDao
    abstract fun organize(): OrganizeDao

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
