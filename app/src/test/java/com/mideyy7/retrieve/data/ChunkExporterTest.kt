package com.mideyy7.retrieve.data

import com.mideyy7.retrieve.ingest.ImportRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkExporterTest {
    private val doc = Document(id = 7, sha256 = "abc123" + "0".repeat(58), name = "w4.pdf", module = "COMP26120",
        week = 4, type = DocType.SLIDES, sourceUri = "content://x", importedAt = 1_795_000_000_000)

    private fun chunk(page: Int, text: String) = Chunk(
        id = page.toLong(), stableKey = StableKeys.page(doc.sha256, page, 0), docId = 7, module = "COMP26120",
        week = 4, page = page, startMs = null, text = text,
    )

    @Test
    fun outputIsSortedAndDeterministic() {
        val a = ChunkExporter.toJson("0.1.0", listOf(doc), listOf(chunk(2, "b"), chunk(1, "a")))
        val b = ChunkExporter.toJson("0.1.0", listOf(doc), listOf(chunk(1, "a"), chunk(2, "b")))
        assertEquals(a, b)
        assertTrue(a.indexOf("p1:0") < a.indexOf("p2:0"))
    }

    @Test
    fun includesFieldsNeededForLabels() {
        val json = ChunkExporter.toJson("0.1.0", listOf(doc), listOf(chunk(1, "Raft \"log\" matching")))
        assertTrue(json.contains("\"format_version\": 1"))
        assertTrue(json.contains("\"ocr\": false"))
        assertTrue(json.contains("\"doc_sha256\": \"${doc.sha256}\""))
        assertTrue(json.contains("\"page\": 1"))
        assertTrue(json.contains("\"start_ms\": null"))
        assertTrue(json.contains("Raft \\\"log\\\" matching")) // escaped correctly
        assertTrue(!json.contains("1795000000000")) // no timestamps leak into the export
    }

    @Test
    fun docTagRoundTrips() {
        assertEquals(42L, ImportRepository.docIdFromTags(setOf("indexing", ImportRepository.docTag(42))))
        assertNull(ImportRepository.docIdFromTags(setOf("indexing", "com.example.Worker")))
    }
}
