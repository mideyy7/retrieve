package com.mideyy7.retrieve.ui.viewer

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.ingest.Chunker
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPagesTest {
    @Test
    fun joiningChunksGivesBackTheWholePage() {
        val words = (1..523).joinToString(" ") { "w$it" }
        val parts = Chunker().chunkText(words)
        assertEquals(4, parts.size)
        assertEquals(words, joinPageChunks(parts))
    }

    private fun chunk(id: Long, page: Int?, startMs: Long?, text: String) =
        Chunk(id = id, stableKey = "k$id", docId = 1, module = "M", week = 1, page = page, startMs = startMs, text = text)

    @Test
    fun pdfListsEveryPageIncludingOnesWithoutText() {
        val pages = viewerPages(DocType.SLIDES, listOf(chunk(1, 1, null, "intro"), chunk(2, 3, null, "raft")), pageCount = 4)
        assertEquals(listOf(1, 2, 3, 4), pages.map { it.page })
        assertEquals(listOf("intro", "", "raft", ""), pages.map { it.text })
    }

    @Test
    fun withoutTheFileThePagesComeFromTheIndex() {
        val pages = viewerPages(DocType.PDF, listOf(chunk(1, 2, null, "b")), pageCount = null)
        assertEquals(listOf(1, 2), pages.map { it.page })
    }

    @Test
    fun recordingIsListedByTime() {
        val pages = viewerPages(DocType.AUDIO, listOf(chunk(2, null, 30_000, "b"), chunk(1, null, 0, "a")), pageCount = null)
        assertEquals(listOf(0L, 30_000L), pages.map { it.startMs })
    }
}
