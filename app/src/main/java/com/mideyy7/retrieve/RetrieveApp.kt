package com.mideyy7.retrieve

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.ingest.ImportRepository
import com.mideyy7.retrieve.ingest.LibraryRepository
import com.mideyy7.retrieve.search.KeywordIndexStore
import com.mideyy7.retrieve.search.SemanticIndex
import com.mideyy7.retrieve.search.TextEmbedder
import com.mideyy7.retrieve.work.EmbedWorker

/**
 * Application-wide singletons. Kept lazy and free of native calls in onCreate: Robolectric runs
 * unit tests with this Application class on the JVM, where the native library can't be loaded.
 */
class RetrieveApp : Application() {
    val database: RetrieveDatabase by lazy { RetrieveDatabase.get(this) }
    val keywordIndex: KeywordIndexStore by lazy { KeywordIndexStore(this, database) }
    val embedder: TextEmbedder by lazy { TextEmbedder(this) }
    val semanticIndex: SemanticIndex by lazy { SemanticIndex(database) }
    val imports: ImportRepository by lazy { ImportRepository(this, database, WorkManager.getInstance(this)) }
    val library: LibraryRepository by lazy {
        LibraryRepository(database, WorkManager.getInstance(this), imports, keywordIndex, semanticIndex)
    }

    /**
     * Called once per launch: if the bundled embedding model changed since the stored vectors were
     * made, clear them (vectors from different models aren't comparable), then embed whatever is
     * missing and load the semantic index.
     */
    suspend fun prepareSemanticSearch() {
        val prefs = getSharedPreferences("embedding", MODE_PRIVATE)
        val current = embedder.info.sha256
        if (prefs.getString("model_sha256", null) != current) {
            database.chunks().clearEmbeddings()
            prefs.edit().putString("model_sha256", current).apply()
        }
        semanticIndex.reload()
        EmbedWorker.enqueue(this)
    }
}

val Context.retrieveApp: RetrieveApp get() = applicationContext as RetrieveApp
