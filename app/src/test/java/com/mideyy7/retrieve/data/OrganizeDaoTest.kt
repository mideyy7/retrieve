package com.mideyy7.retrieve.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Module/week grouping queries, module titles, and moving or renaming material. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrganizeDaoTest {
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

    private var nextSha = 0

    /** Inserts a document (READY with [pages] chunks unless [status] says otherwise) and returns its id and sha. */
    private suspend fun add(
        module: String,
        week: Int,
        pages: Int = 2,
        importedAt: Long = 0,
        status: DocStatus = DocStatus.READY,
    ): Pair<Long, String> {
        val sha = (nextSha++).toString().padEnd(64, 'a') // stable keys use the first 12 chars
        val id = db.documents().insert(
            Document(sha256 = sha, name = "$module-w$week.pdf", module = module, week = week, type = DocType.SLIDES,
                sourceUri = "content://x/$sha", importedAt = importedAt),
        )
        if (status == DocStatus.READY) {
            db.ingest().replaceChunksAndMarkReady(id, (1..pages).map { chunk(id, sha, it, module, week) })
        } else {
            db.documents().setStatus(id, status, if (status == DocStatus.FAILED) "broken" else null)
        }
        return id to sha
    }

    private fun chunk(docId: Long, sha: String, page: Int, module: String, week: Int) =
        Chunk(stableKey = StableKeys.page(sha, page, 0), docId = docId, module = module, week = week,
            page = page, startMs = null, text = "page $page")

    @Test
    fun modulesAreGroupedWithExactCountsNewestFirst() = runTest {
        add("COMP26120", 1, pages = 3, importedAt = 10)
        add("COMP26120", 2, importedAt = 20, status = DocStatus.FAILED)
        add("COMP26120", 2, importedAt = 30, status = DocStatus.INDEXING)
        add("COMP21111", 4, pages = 5, importedAt = 40)
        db.modules().setTitle("COMP26120", "  Algorithms  ")

        val modules = db.documents().observeModules().first()
        assertEquals(listOf("COMP21111", "COMP26120"), modules.map { it.module })
        val algo = modules[1]
        assertEquals("Algorithms", algo.title)
        assertEquals(3, algo.docCount)
        assertEquals(3, algo.chunkCount)
        assertEquals(1, algo.readyCount)
        assertEquals(1, algo.failedCount)
        assertEquals(1, algo.busyCount)
        assertNull(modules[0].title)
        assertEquals(5, modules[0].chunkCount)
    }

    @Test
    fun weekStatusCountsPerModuleWeekAndStatus() = runTest {
        add("COMP26120", 1)
        add("COMP26120", 1)
        add("COMP26120", 2, status = DocStatus.FAILED)
        val rows = db.documents().observeWeekStatus().first().toSet()
        assertEquals(
            setOf(
                WeekStatusCount("COMP26120", 1, DocStatus.READY, 2),
                WeekStatusCount("COMP26120", 2, DocStatus.FAILED, 1),
            ),
            rows,
        )
    }

    @Test
    fun blankTitleRemovesIt() = runTest {
        db.modules().setTitle("COMP26120", "Algorithms")
        db.modules().setTitle("COMP26120", "   ")
        assertNull(db.modules().observeTitle("COMP26120").first())
    }

    @Test
    fun moveUpdatesDocumentAndChunksAndKeepsStableKeys() = runTest {
        val (id, _) = add("COMP26120", 3, pages = 3)
        val keysBefore = db.chunks().forDoc(id).map { it.stableKey }

        db.organize().moveDocument(id, "COMP21111", Weeks.WHOLE_MODULE)

        val doc = db.documents().byId(id)!!
        assertEquals("COMP21111" to 0, doc.module to doc.week)
        val chunks = db.chunks().forDoc(id)
        assertEquals(keysBefore, chunks.map { it.stableKey })
        assertEquals(setOf("COMP21111" to 0), chunks.map { it.module to it.week }.toSet())
        assertEquals(3, db.chunks().byModuleWeeks("COMP21111", 0, 0).size)
    }

    @Test
    fun chunksCommittedAfterAMoveFollowTheDocument() = runTest {
        // A worker started indexing in week 3, the user moved the document to week 5, then the
        // worker committed chunks it had built with week 3.
        val (id, sha) = add("COMP26120", 3, status = DocStatus.INDEXING)
        db.organize().moveDocument(id, "COMP26120", 5)
        db.ingest().replaceChunksAndMarkReady(id, (1..2).map { chunk(id, sha, it, "COMP26120", 3) })

        assertEquals(setOf("COMP26120" to 5), db.chunks().forDoc(id).map { it.module to it.week }.toSet())
    }

    @Test
    fun renameModuleMovesEverythingAndCarriesTheTitle() = runTest {
        val (a, _) = add("COMP2612O", 1)
        val (b, _) = add("COMP2612O", 2)
        db.modules().setTitle("COMP2612O", "Algorithms")

        db.organize().renameModule("COMP2612O", "COMP26120")

        assertEquals(setOf("COMP26120"), listOf(a, b).map { db.documents().byId(it)!!.module }.toSet())
        assertEquals(setOf("COMP26120"), (db.chunks().forDoc(a) + db.chunks().forDoc(b)).map { it.module }.toSet())
        assertEquals("Algorithms", db.modules().observeTitle("COMP26120").first())
        assertNull(db.modules().observeTitle("COMP2612O").first())
        assertEquals(0, db.organize().documentCount("COMP2612O"))
    }

    @Test
    fun renameIntoExistingModuleMergesAndKeepsTargetTitle() = runTest {
        add("COMP2612O", 1)
        add("COMP26120", 2)
        db.modules().setTitle("COMP2612O", "Typo")
        db.modules().setTitle("COMP26120", "Algorithms")

        db.organize().renameModule("COMP2612O", "COMP26120")

        val modules = db.documents().observeModules().first()
        assertEquals(listOf("COMP26120"), modules.map { it.module })
        assertEquals(2, modules.single().docCount)
        assertEquals("Algorithms", modules.single().title)
    }

    @Test
    fun moduleDocumentsAreOrderedByWeekThenName() = runTest {
        add("COMP26120", 4)
        add("COMP26120", 0)
        add("COMP26120", 2)
        add("COMP21111", 1)
        val weeks = db.documents().observeModuleSummaries("COMP26120").first().map { it.week }
        assertEquals(listOf(0, 2, 4), weeks)
    }
}
