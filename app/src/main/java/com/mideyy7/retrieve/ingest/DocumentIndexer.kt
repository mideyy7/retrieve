package com.mideyy7.retrieve.ingest

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.RetrieveDatabase
import com.mideyy7.retrieve.data.StableKeys
import java.io.Closeable

/** An opened PDF: its text layer and an OCR engine for the same file. */
class OpenedPdf(val text: PageTextSource, val ocr: PageOcr, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

sealed interface IndexOutcome {
    data class Indexed(val pages: Int, val ocrPages: Int, val chunks: Int) : IndexOutcome
    data object DocumentGone : IndexOutcome
    data class Failed(val reason: String) : IndexOutcome
}

/**
 * Turns one imported PDF into chunks in the database. Safe to run repeatedly for the same
 * document: chunks are replaced atomically, so a retried or restarted job ends in the same state.
 */
class DocumentIndexer(
    private val db: RetrieveDatabase,
    private val openPdf: (Document) -> OpenedPdf,
    private val extractor: PdfTextExtractor = PdfTextExtractor(),
    private val chunker: Chunker = Chunker(),
) {
    suspend fun index(docId: Long, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): IndexOutcome {
        val doc = db.documents().byId(docId) ?: return IndexOutcome.DocumentGone
        db.documents().setStatus(docId, DocStatus.INDEXING)

        val pages = try {
            openPdf(doc).use { pdf -> extractor.extract(pdf.text, pdf.ocr, onProgress) }
        } catch (e: PermanentImportError) {
            db.documents().setStatus(docId, DocStatus.FAILED, e.message)
            return IndexOutcome.Failed(e.message ?: "import failed")
        }

        val chunks = chunker.chunkPages(pages.map { it.text }).map { pc ->
            Chunk(
                stableKey = StableKeys.page(doc.sha256, pc.page, pc.index),
                docId = doc.id,
                module = doc.module,
                week = doc.week,
                page = pc.page,
                startMs = null,
                text = pc.text,
            )
        }
        db.ingest().replaceChunksAndMarkReady(doc.id, chunks)
        return IndexOutcome.Indexed(pages = pages.size, ocrPages = pages.count { it.ocr }, chunks = chunks.size)
    }
}

/** An error that retrying won't fix (file gone, permission revoked, encrypted or corrupt PDF). */
class PermanentImportError(message: String, cause: Throwable? = null) : Exception(message, cause)
