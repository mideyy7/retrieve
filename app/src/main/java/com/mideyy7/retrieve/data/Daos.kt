package com.mideyy7.retrieve.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    /** Returns the new row id, or -1 if a document with the same sha256 already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(doc: Document): Long

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: Long): Document?

    @Query("SELECT * FROM documents WHERE sha256 = :sha256")
    suspend fun bySha(sha256: String): Document?

    @Query("UPDATE documents SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DocStatus, error: String? = null)

    @Query("SELECT * FROM documents WHERE status = 'READY'")
    suspend fun allReady(): List<Document>

    @Query("SELECT id FROM documents ORDER BY id")
    suspend fun allIds(): List<Long>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        SELECT d.id, d.name, d.module, d.week, d.type, d.status, d.error, COUNT(c.id) AS chunkCount
        FROM documents d LEFT JOIN chunks c ON c.docId = d.id
        GROUP BY d.id
        ORDER BY d.module, d.week, d.name
        """,
    )
    fun observeSummaries(): Flow<List<DocumentSummary>>

    @Query(
        """
        SELECT d.id, d.name, d.module, d.week, d.type, d.status, d.error, COUNT(c.id) AS chunkCount
        FROM documents d LEFT JOIN chunks c ON c.docId = d.id
        WHERE d.module = :module
        GROUP BY d.id
        ORDER BY d.week, d.name COLLATE NOCASE
        """,
    )
    fun observeModuleSummaries(module: String): Flow<List<DocumentSummary>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: Long): Flow<Document?>

    /** One row per module, most recently imported first. Counts come from this one query, so they always agree. */
    @Query(
        """
        SELECT d.module AS module, m.title AS title, COUNT(*) AS docCount,
            (SELECT COUNT(*) FROM chunks c WHERE c.module = d.module) AS chunkCount,
            SUM(CASE WHEN d.status = 'READY' THEN 1 ELSE 0 END) AS readyCount,
            SUM(CASE WHEN d.status = 'FAILED' THEN 1 ELSE 0 END) AS failedCount,
            SUM(CASE WHEN d.status IN ('PENDING', 'INDEXING') THEN 1 ELSE 0 END) AS busyCount,
            MAX(d.importedAt) AS lastImportedAt
        FROM documents d LEFT JOIN modules m ON m.code = d.module
        GROUP BY d.module
        ORDER BY lastImportedAt DESC, d.module
        """,
    )
    fun observeModules(): Flow<List<ModuleSummary>>

    @Query("SELECT module, week, status, COUNT(*) AS count FROM documents GROUP BY module, week, status")
    fun observeWeekStatus(): Flow<List<WeekStatusCount>>
}

@Dao
abstract class ModuleDao {
    @Upsert
    protected abstract suspend fun upsert(info: ModuleInfo)

    @Query("DELETE FROM modules WHERE code = :code")
    protected abstract suspend fun delete(code: String)

    @Query("SELECT title FROM modules WHERE code = :code")
    abstract fun observeTitle(code: String): Flow<String?>

    /** Sets the module's title; a blank title removes it. */
    open suspend fun setTitle(code: String, title: String?) {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) delete(code) else upsert(ModuleInfo(code, t))
    }
}

/**
 * Moves and renames. Module and week are copied onto every chunk (filters need them without a
 * join), so each change updates documents and chunks together in one transaction. The caller then
 * rebuilds the keyword index, which resolves filters from the chunks it was built with.
 * Stable keys come from the file hash and page, so moving never changes a chunk's identity.
 */
@Dao
abstract class OrganizeDao {
    @Query("UPDATE documents SET module = :module, week = :week WHERE id = :docId")
    protected abstract suspend fun moveDocumentRow(docId: Long, module: String, week: Int)

    @Query("UPDATE chunks SET module = :module, week = :week WHERE docId = :docId")
    protected abstract suspend fun moveDocumentChunks(docId: Long, module: String, week: Int)

    @Transaction
    open suspend fun moveDocument(docId: Long, module: String, week: Int) {
        moveDocumentRow(docId, module, week)
        moveDocumentChunks(docId, module, week)
    }

    @Query("UPDATE documents SET module = :to WHERE module = :from")
    protected abstract suspend fun renameModuleDocuments(from: String, to: String)

    @Query("UPDATE chunks SET module = :to WHERE module = :from")
    protected abstract suspend fun renameModuleChunks(from: String, to: String)

    /** Keeps the old title when the new code has none of its own (a plain rename); a merge keeps the target's. */
    @Query("INSERT OR IGNORE INTO modules (code, title) SELECT :to, title FROM modules WHERE code = :from")
    protected abstract suspend fun carryTitle(from: String, to: String)

    @Query("DELETE FROM modules WHERE code = :code")
    protected abstract suspend fun deleteTitle(code: String)

    /** Renames a module code everywhere. If [to] already exists, the two modules merge. */
    @Transaction
    open suspend fun renameModule(from: String, to: String) {
        if (from == to) return
        renameModuleDocuments(from, to)
        renameModuleChunks(from, to)
        carryTitle(from, to)
        deleteTitle(from)
    }

    @Query("UPDATE documents SET name = :name WHERE id = :docId")
    abstract suspend fun renameDocument(docId: Long, name: String)

    @Query("SELECT COUNT(*) FROM documents WHERE module = :code")
    abstract suspend fun documentCount(code: String): Int
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<Chunk>)

    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteForDoc(docId: Long)

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY id")
    suspend fun forDoc(docId: Long): List<Chunk>

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY page, startMs, id")
    fun observeForDoc(docId: Long): Flow<List<Chunk>>

    @Query("SELECT COUNT(*) FROM chunks WHERE docId = :docId")
    suspend fun countForDoc(docId: Long): Int

    @Query("SELECT * FROM chunks WHERE module = :module AND week BETWEEN :weekFrom AND :weekTo ORDER BY id")
    suspend fun byModuleWeeks(module: String, weekFrom: Int, weekTo: Int): List<Chunk>

    @Query(
        """
        SELECT c.*, d.name AS docName, d.type AS docType, d.sha256 AS docSha FROM chunks c JOIN documents d ON d.id = c.docId
        WHERE c.stableKey IN (:keys)
        """,
    )
    suspend fun withDocByStableKeys(keys: List<String>): List<ChunkWithDoc>

    // ---- Embeddings (Phase 5) ----

    @Query(
        """
        SELECT c.id, c.text FROM chunks c JOIN documents d ON d.id = c.docId
        WHERE d.status = 'READY' AND c.embedding IS NULL ORDER BY c.id LIMIT :limit
        """,
    )
    suspend fun missingEmbeddings(limit: Int): List<ChunkText>

    @Query("SELECT COUNT(*) FROM chunks c JOIN documents d ON d.id = c.docId WHERE d.status = 'READY' AND c.embedding IS NULL")
    suspend fun countMissingEmbeddings(): Int

    @Query("UPDATE chunks SET embedding = :embedding WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray)

    @Query("UPDATE chunks SET embedding = NULL")
    suspend fun clearEmbeddings()

    /** Ordered by stableKey, the same order as chunks.json, so tie-breaking matches ml/eval.py. */
    @Query(
        """
        SELECT c.stableKey, c.embedding FROM chunks c JOIN documents d ON d.id = c.docId
        WHERE d.status = 'READY' AND c.embedding IS NOT NULL ORDER BY c.stableKey
        """,
    )
    suspend fun allEmbeddings(): List<ChunkEmbedding>

    /** Only chunks of READY documents, so exports never contain a half-finished import. */
    @Query(
        """
        SELECT c.* FROM chunks c JOIN documents d ON d.id = c.docId
        WHERE d.status = 'READY' ORDER BY c.stableKey
        """,
    )
    suspend fun allReady(): List<Chunk>
}

@Dao
abstract class IngestDao {
    @Query("DELETE FROM chunks WHERE docId = :docId")
    protected abstract suspend fun deleteChunks(docId: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertChunks(chunks: List<Chunk>)

    @Query("UPDATE documents SET status = 'READY', error = NULL WHERE id = :docId")
    protected abstract suspend fun markReady(docId: Long)

    /**
     * The worker built its chunks from the document as it was when indexing started. If the
     * document was moved since, the row is the truth, so copy its module and week onto the chunks.
     */
    @Query(
        """
        UPDATE chunks SET
            module = (SELECT module FROM documents WHERE id = :docId),
            week = (SELECT week FROM documents WHERE id = :docId)
        WHERE docId = :docId
        """,
    )
    protected abstract suspend fun syncChunkPlacement(docId: Long)

    /**
     * Atomically replace a document's chunks and mark it READY. Either all of this happens or
     * none of it does, so a worker killed mid-import leaves no partial state, and a retried
     * worker can simply run again.
     */
    @Transaction
    open suspend fun replaceChunksAndMarkReady(docId: Long, chunks: List<Chunk>) {
        deleteChunks(docId)
        insertChunks(chunks)
        syncChunkPlacement(docId)
        markReady(docId)
    }
}
