package com.mideyy7.retrieve.search

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Runs on the device: exercises the real JNI bridge and the NDK-built engine. Uses the same
 * hand-computed corpus and BM25 values as native/tests/bm25_test.cpp, so the phone must produce
 * exactly what the desktop tests check.
 */
@RunWith(AndroidJUnit4::class)
class KeywordIndexDeviceTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "kwtest").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun chunk(key: String, module: String, week: Int, text: String, page: Int? = 1, startMs: Long? = null) =
        Chunk(stableKey = key, docId = 1, module = module, week = week, page = page, startMs = startMs, text = text)

    private val corpus = listOf(
        chunk("d2", "COMP26120", 6, "raft log matching raft"),
        chunk("d0", "COMP26120", 4, "the raft leader"),
        chunk("d1", "COMP25111", 5, "log replication of the log", page = null, startMs = 30_000),
    )

    private fun buildAndOpen(chunks: List<Chunk> = corpus): KeywordIndex {
        val file = File(dir, "test.idx")
        KeywordIndex.build(file, chunks) { DocType.SLIDES }
        return KeywordIndex.open(file)
    }

    @Test
    fun bm25ScoresMatchDesktopHandComputedValues() {
        buildAndOpen().use { idx ->
            // raft, log, matching, the, leader, replication, of
            assertEquals(KeywordIndex.IndexStats(chunks = 3, terms = 7, fileBytes = idx.stats.fileBytes), idx.stats)
            val hits = idx.search("raft", 10)
            assertEquals(listOf("d2", "d0"), hits.map { it.stableKey })
            assertEquals(0.646254990213f, hits[0].score, 1e-6f)
            assertEquals(0.523548346502f, hits[1].score, 1e-6f)
            assertEquals(listOf("d2"), idx.search("\"raft log\"", 10).map { it.stableKey })
        }
    }

    @Test
    fun supplementaryCharactersAreEncodedAsStandardUtf8() {
        // Math italic x (U+1D465) is outside the BMP. With JNI's modified UTF-8 it would become a
        // 6-byte surrogate encoding and never match a query typed with the same character.
        buildAndOpen(listOf(chunk("m", "M", 1, "let 𝑥 be free"))).use { idx ->
            assertEquals(listOf("m"), idx.search("𝑥", 10).map { it.stableKey })
            assertEquals(4, idx.stats.terms)
        }
    }

    @Test
    fun corruptFileThrowsIOExceptionInsteadOfCrashing() {
        val file = File(dir, "corrupt.idx").apply { writeBytes(ByteArray(200) { 7 }) }
        try {
            KeywordIndex.open(file)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("bad magic"))
        }
    }

    @Test
    fun closedIndexRejectsSearchAndDoubleCloseIsSafe() {
        val idx = buildAndOpen()
        idx.close()
        idx.close()
        try {
            idx.search("raft", 5)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun buildingMoreChunksThanTheLocalReferenceLimitWorks() {
        // Android allows a few hundred live local references per native call; building must free them.
        val many = (0 until 3000).map { chunk("k%05d".format(it), "M", 1, "term$it shared words here") }
        buildAndOpen(many).use { idx ->
            assertEquals(3000, idx.stats.chunks)
            assertEquals(listOf("k01234"), idx.search("term1234", 5).map { it.stableKey })
        }
    }

    @Test
    fun thousandSearchesDoNotLeakNativeMemory() {
        buildAndOpen().use { idx ->
            repeat(200) { idx.search("raft log the leader", 10) } // warm up allocator
            System.gc()
            val before = Debug.getNativeHeapAllocatedSize()
            repeat(1000) { idx.search("raft log the leader", 10) }
            System.gc()
            val growth = Debug.getNativeHeapAllocatedSize() - before
            assertTrue("native heap grew by $growth bytes over 1000 searches", growth < 256 * 1024)
        }
    }
}
