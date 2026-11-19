package com.mideyy7.retrieve.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Document::class, Chunk::class], version = 1, exportSchema = true)
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
