package com.mideyy7.retrieve.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.RetrieveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface IndexState {
    data object NotLoaded : IndexState
    data class Ready(val stats: KeywordIndex.IndexStats, val buildMs: Long?) : IndexState
    data class Error(val message: String) : IndexState
}

/**
 * Owns the app's one keyword index. Room is the source of truth; the index file is derived from
 * it and can always be rebuilt:
 *  - after each document finishes indexing or is deleted, and
 *  - once in the background at app start, which repairs an index left stale by a crash between
 *    committing chunks and rebuilding.
 * The previous file is opened immediately on start, so search works while that rebuild runs.
 *
 * Rebuilds are serialised by a mutex (two writers would race on the file); the new index is
 * swapped in and the old one closed, and KeywordIndex's lock makes that safe against searches.
 */
class KeywordIndexStore(context: Context, private val db: RetrieveDatabase) {
    private val file = File(context.filesDir, "index/keyword.idx")
    private val buildMutex = Mutex()

    @Volatile private var current: KeywordIndex? = null

    private val _state = MutableStateFlow<IndexState>(IndexState.NotLoaded)
    val state: StateFlow<IndexState> = _state.asStateFlow()

    /** Opens the existing index file if there is one, then rebuilds from the database. */
    suspend fun openThenRebuild() {
        withContext(Dispatchers.IO) {
            if (current == null && file.exists()) {
                try {
                    swapIn(KeywordIndex.open(file), buildMs = null)
                } catch (e: IOException) {
                    Log.w(TAG, "existing index unreadable, rebuilding", e)
                }
            }
        }
        rebuild()
    }

    suspend fun rebuild() = buildMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val start = SystemClock.elapsedRealtime()
                val types = db.documents().allReady().associate { it.id to it.type }
                val chunks = db.chunks().allReady()
                KeywordIndex.build(file, chunks) { types[it.docId] ?: DocType.PDF }
                val buildMs = SystemClock.elapsedRealtime() - start
                val opened = KeywordIndex.open(file)
                swapIn(opened, buildMs)
                Log.i(TAG, "rebuilt index: ${opened.stats} in $buildMs ms")
            } catch (e: IOException) {
                Log.e(TAG, "index rebuild failed", e)
                _state.value = IndexState.Error(e.message ?: "index rebuild failed")
            }
        }
    }

    /** Top-k keyword hits. Empty until an index has been opened. */
    fun search(query: String, k: Int): List<KeywordHit> {
        repeat(2) {
            val idx = current ?: return emptyList()
            try {
                return idx.search(query, k)
            } catch (_: IllegalStateException) {
                // Closed by a concurrent swap between reading `current` and searching: retry once on the new one.
            }
        }
        return emptyList()
    }

    private fun swapIn(index: KeywordIndex, buildMs: Long?) {
        val old = current
        current = index
        old?.close()
        _state.value = IndexState.Ready(index.stats, buildMs)
    }

    companion object {
        private const val TAG = "KeywordIndexStore"
    }
}
