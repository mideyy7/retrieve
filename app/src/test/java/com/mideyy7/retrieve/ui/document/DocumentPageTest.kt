package com.mideyy7.retrieve.ui.document

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.ui.RetrieveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun chunk(page: Int, index: Int, text: String, ocr: Boolean = false) =
        Chunk(stableKey = "k:p$page:$index", docId = 1, module = "COMP26120", week = 0, page = page, startMs = null, text = text, ocr = ocr)

    private fun doc(status: DocStatus = DocStatus.READY, error: String? = null) =
        Document(1, "a".repeat(64), "Textbook.pdf", "COMP26120", 0, DocType.PDF, status, "content://x", 0, error)

    @Test
    fun chunksOfOnePageAreListedOnce() {
        val rows = pageRows(listOf(chunk(1, 0, "Raft  log"), chunk(1, 1, "matching", ocr = true), chunk(2, 0, "Paxos")))
        assertEquals(listOf(1, 2), rows.map { it.page })
        assertEquals("Raft log matching", rows[0].preview)
        assertEquals(2, rows[0].parts)
        assertTrue(rows[0].ocr)
    }

    @Test
    fun showsPlacementPagesAndOcr() {
        val pages = pageRows(listOf(chunk(1, 0, "Raft log", ocr = true), chunk(2, 0, "Paxos")))
        compose.setContent { RetrieveTheme { DocumentContent(doc(), pages, onRetry = {}) } }
        compose.onNodeWithText("COMP26120 › Whole module › PDF").assertExists()
        compose.onNodeWithText("PDF · 2 chunks").assertExists()
        compose.onNodeWithText("2 pages · 1 read with OCR", substring = true).assertExists()
        compose.onNodeWithText("Page 1").assertExists()
        compose.onNodeWithText("OCR").assertExists()
    }

    @Test
    fun failedDocumentCanBeRetried() {
        var retried = false
        compose.setContent {
            RetrieveTheme { DocumentContent(doc(DocStatus.FAILED, "Couldn't open file"), emptyList(), onRetry = { retried = true }) }
        }
        compose.onNodeWithText("Failed: Couldn't open file").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }
}
