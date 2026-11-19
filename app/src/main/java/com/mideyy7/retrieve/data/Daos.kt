package com.mideyy7.retrieve.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
}

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<Chunk>)

    @Query("DELETE FROM chunks WHERE docId = :docId")
    suspend fun deleteForDoc(docId: Long)

    @Query("SELECT * FROM chunks WHERE docId = :docId ORDER BY id")
    suspend fun forDoc(docId: Long): List<Chunk>

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
     * Atomically replace a document's chunks and mark it READY. Either all of this happens or
     * none of it does, so a worker killed mid-import leaves no partial state, and a retried
     * worker can simply run again.
     */
    @Transaction
    open suspend fun replaceChunksAndMarkReady(docId: Long, chunks: List<Chunk>) {
        deleteChunks(docId)
        insertChunks(chunks)
        markReady(docId)
    }
}
