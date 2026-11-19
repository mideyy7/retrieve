package com.mideyy7.retrieve.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaoTest {
    private lateinit var db: RetrieveDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RetrieveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun doc(sha: String = "a".repeat(64), module: String = "COMP26120", week: Int = 4) =
        Document(sha256 = sha, name = "w$week.pdf", module = module, week = week, type = DocType.SLIDES,
            sourceUri = "content://x/$sha", importedAt = 0)

    private fun chunk(docId: Long, sha: String, page: Int, module: String = "COMP26120", week: Int = 4) =
        Chunk(stableKey = StableKeys.page(sha, page, 0), docId = docId, module = module, week = week,
            page = page, startMs = null, text = "page $page")

    @Test
    fun duplicateShaIsIgnored() = runTest {
        val first = db.documents().insert(doc())
        val second = db.documents().insert(doc())
        assertTrue(first > 0)
        assertEquals(-1L, second)
        assertEquals(first, db.documents().bySha("a".repeat(64))!!.id)
    }

    @Test
    fun setSourceUriRepointsTheDocument() = runTest {
        val id = db.documents().insert(doc("7".repeat(64)))
        db.documents().setSourceUri(id, "content://new/uri")
        assertEquals("content://new/uri", db.documents().byId(id)!!.sourceUri)
    }

    @Test
    fun replaceChunksIsAtomicAndIdempotent() = runTest {
        val sha = "b".repeat(64)
        val id = db.documents().insert(doc(sha))
        val chunks = (1..3).map { chunk(id, sha, it) }

        db.ingest().replaceChunksAndMarkReady(id, chunks)
        db.ingest().replaceChunksAndMarkReady(id, chunks) // retried worker: same result, no duplicates

        assertEquals(3, db.chunks().countForDoc(id))
        assertEquals(DocStatus.READY, db.documents().byId(id)!!.status)
    }

    @Test
    fun failedReplaceLeavesNoPartialState() = runTest {
        val sha = "c".repeat(64)
        val id = db.documents().insert(doc(sha))
        val dup = chunk(id, sha, 1)
        try {
            db.ingest().replaceChunksAndMarkReady(id, listOf(dup, dup)) // unique stableKey violated
        } catch (_: SQLiteConstraintException) {
        }
        assertEquals(0, db.chunks().countForDoc(id))
        assertEquals(DocStatus.PENDING, db.documents().byId(id)!!.status)
    }

    @Test
    fun queryByModuleAndWeekRange() = runTest {
        for ((i, week) in listOf(3, 4, 5, 6, 7).withIndex()) {
            val sha = "$i".repeat(64)
            val id = db.documents().insert(doc(sha, week = week))
            db.ingest().replaceChunksAndMarkReady(id, listOf(chunk(id, sha, 1, week = week)))
        }
        val other = db.documents().insert(doc("f".repeat(64), module = "COMP25111", week = 5))
        db.ingest().replaceChunksAndMarkReady(other, listOf(chunk(other, "f".repeat(64), 1, "COMP25111", 5)))

        val hits = db.chunks().byModuleWeeks("COMP26120", 4, 6)
        assertEquals(listOf(4, 5, 6), hits.map { it.week })
    }

    @Test
    fun deletingDocumentCascadesToChunks() = runTest {
        val sha = "d".repeat(64)
        val id = db.documents().insert(doc(sha))
        db.ingest().replaceChunksAndMarkReady(id, listOf(chunk(id, sha, 1)))
        db.documents().delete(id)
        assertEquals(0, db.chunks().countForDoc(id))
        assertNull(db.documents().byId(id))
    }

    @Test
    fun exportOnlyIncludesReadyDocuments() = runTest {
        val readySha = "e".repeat(64)
        val ready = db.documents().insert(doc(readySha))
        db.ingest().replaceChunksAndMarkReady(ready, listOf(chunk(ready, readySha, 1)))
        db.documents().insert(doc("9".repeat(64))) // still PENDING, no chunks

        assertEquals(listOf(StableKeys.page(readySha, 1, 0)), db.chunks().allReady().map { it.stableKey })
    }

    @Test
    fun summariesCountChunks() = runTest {
        val sha = "1".repeat(64)
        val id = db.documents().insert(doc(sha))
        db.ingest().replaceChunksAndMarkReady(id, (1..4).map { chunk(id, sha, it) })
        val summary = db.documents().observeSummaries().first().single()
        assertEquals(4, summary.chunkCount)
        assertEquals(DocStatus.READY, summary.status)
    }
}
