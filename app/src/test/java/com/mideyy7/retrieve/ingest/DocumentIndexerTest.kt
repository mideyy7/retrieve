package com.mideyy7.retrieve.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.RetrieveDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentIndexerTest {
    private lateinit var db: RetrieveDatabase
    private val sha = "0123456789ab" + "f".repeat(52)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RetrieveDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertDoc() = db.documents().insert(
        Document(sha256 = sha, name = "w4.pdf", module = "COMP26120", week = 4, type = DocType.SLIDES,
            sourceUri = "content://x", importedAt = 0),
    )

    private fun fakePdf(pages: List<String>, ocr: Map<Int, String> = emptyMap(), closed: MutableList<Boolean>? = null) =
        { _: Document ->
            OpenedPdf(
                text = object : PageTextSource {
                    override val pageCount = pages.size
                    override fun text(pageIndex: Int) = pages[pageIndex]
                },
                ocr = PageOcr { ocr[it].orEmpty() },
                onClose = { closed?.add(true) },
            )
        }

    private val slide = "Raft log matching: if two logs contain an entry with the same index and term"

    @Test
    fun indexesPagesIntoChunksWithStableKeysAndMarksReady() = runTest {
        val id = insertDoc()
        val closed = mutableListOf<Boolean>()
        val outcome = DocumentIndexer(db, fakePdf(listOf(slide, " ", slide), ocr = mapOf(1 to "Diagram of leader election terms"), closed))
            .index(id)

        assertEquals(IndexOutcome.Indexed(pages = 3, ocrPages = 1, chunks = 3), outcome)
        val chunks = db.chunks().forDoc(id)
        assertEquals(listOf("0123456789ab:p1:0", "0123456789ab:p2:0", "0123456789ab:p3:0"), chunks.map { it.stableKey })
        assertEquals(listOf(1, 2, 3), chunks.map { it.page })
        assertTrue(chunks.all { it.module == "COMP26120" && it.week == 4 && it.startMs == null })
        assertEquals(listOf(false, true, false), chunks.map { it.ocr })
        assertEquals(DocStatus.READY, db.documents().byId(id)!!.status)
        assertEquals(listOf(true), closed)
    }

    @Test
    fun rerunningGivesIdenticalChunks() = runTest {
        val id = insertDoc()
        val indexer = DocumentIndexer(db, fakePdf(listOf(slide, slide)))
        indexer.index(id)
        val first = db.chunks().forDoc(id).map { it.stableKey to it.text }
        indexer.index(id)
        assertEquals(first, db.chunks().forDoc(id).map { it.stableKey to it.text })
    }

    @Test
    fun permanentErrorMarksFailedWithNoChunks() = runTest {
        val id = insertDoc()
        val outcome = DocumentIndexer(db, { throw PermanentImportError("w4.pdf is password protected") }).index(id)
        assertEquals(IndexOutcome.Failed("w4.pdf is password protected"), outcome)
        val doc = db.documents().byId(id)!!
        assertEquals(DocStatus.FAILED, doc.status)
        assertEquals("w4.pdf is password protected", doc.error)
        assertEquals(0, db.chunks().countForDoc(id))
    }

    @Test
    fun captionsBecomeTimedChunksKeyedByStartTime() = runTest {
        val id = db.documents().insert(
            Document(sha256 = sha, name = "w3.vtt", module = "COMP23311", week = 3, type = DocType.AUDIO,
                sourceUri = "content://x", importedAt = 0),
        )
        val vtt = "WEBVTT\n\n" + (0 until 12).joinToString("") { i ->
            "00:%02d.000 --> 00:%02d.500\nunit tests sentence $i\n\n".format(i * 5, i * 5 + 4)
        }
        val outcome = DocumentIndexer(db, { error("not a PDF") }, readCaptions = { vtt }).index(id)

        val chunks = db.chunks().forDoc(id)
        assertEquals(IndexOutcome.Indexed(pages = 0, ocrPages = 0, chunks = chunks.size), outcome)
        assertEquals(listOf(0L, 35_000L), chunks.map { it.startMs })
        assertEquals(listOf("0123456789ab:t0", "0123456789ab:t35000"), chunks.map { it.stableKey })
        assertTrue(chunks.all { it.page == null && it.module == "COMP23311" && it.week == 3 })
        assertEquals(DocStatus.READY, db.documents().byId(id)!!.status)
    }

    @Test
    fun emptyCaptionFileFails() = runTest {
        val id = db.documents().insert(
            Document(sha256 = sha, name = "notes.vtt", module = "COMP23311", week = 3, type = DocType.AUDIO,
                sourceUri = "content://x", importedAt = 0),
        )
        val outcome = DocumentIndexer(db, { error("not a PDF") }, readCaptions = { "just some text" }).index(id)
        assertTrue(outcome is IndexOutcome.Failed)
        assertEquals(DocStatus.FAILED, db.documents().byId(id)!!.status)
    }

    @Test
    fun deletedDocumentIsNotAnError() = runTest {
        assertEquals(IndexOutcome.DocumentGone, DocumentIndexer(db, fakePdf(listOf(slide))).index(999))
    }

    @Test
    fun reportsProgress() = runTest {
        val id = insertDoc()
        val seen = mutableListOf<Pair<Int, Int>>()
        DocumentIndexer(db, fakePdf(listOf(slide, slide))).index(id) { d, t -> seen += d to t }
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(ByteArrayInputStream("abc".toByteArray())),
        )
    }
}
