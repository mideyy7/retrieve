package com.mideyy7.retrieve.ingest

import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
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
    private val overlays: OverlayCollapser = OverlayCollapser(),
    /** Reads a recording's caption file (WebVTT). Throws PermanentImportError if it can't be read. */
    private val readCaptions: (Document) -> String = { throw PermanentImportError("captions not supported here") },
) {
    suspend fun index(docId: Long, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): IndexOutcome {
        val doc = db.documents().byId(docId) ?: return IndexOutcome.DocumentGone
        db.documents().setStatus(docId, DocStatus.INDEXING)
        if (doc.type == DocType.AUDIO) return indexCaptions(doc)

        val pages = try {
            openPdf(doc).use { pdf -> overlays.collapse(extractor.extract(pdf.text, pdf.ocr, onProgress)) }
        } catch (e: PermanentImportError) {
            db.documents().setStatus(docId, DocStatus.FAILED, e.message)
            return IndexOutcome.Failed(e.message ?: "import failed")
        }

        val ocrPages = pages.filter { it.ocr }.map { it.page }.toSet()
        val chunks = chunker.chunkPages(pages.map { it.text }).map { pc ->
            Chunk(
                stableKey = StableKeys.page(doc.sha256, pc.page, pc.index),
                docId = doc.id,
                module = doc.module,
                week = doc.week,
                page = pc.page,
                startMs = null,
                text = pc.text,
                ocr = pc.page in ocrPages,
            )
        }
        db.ingest().replaceChunksAndMarkReady(doc.id, chunks)
        return IndexOutcome.Indexed(pages = pages.size, ocrPages = pages.count { it.ocr }, chunks = chunks.size)
    }

    /**
     * A recording, indexed from its captions: cues merged into ~30 s chunks keyed by start time, so a
     * search hit can say (and later play) "12:40". Whisper transcripts will feed the same path.
     */
    private suspend fun indexCaptions(doc: Document): IndexOutcome {
        val cues = try {
            WebVtt.parse(readCaptions(doc))
        } catch (e: PermanentImportError) {
            db.documents().setStatus(doc.id, DocStatus.FAILED, e.message)
            return IndexOutcome.Failed(e.message ?: "import failed")
        }
        if (cues.isEmpty()) {
            val msg = "${doc.name} has no captions (expected a WebVTT .vtt file)"
            db.documents().setStatus(doc.id, DocStatus.FAILED, msg)
            return IndexOutcome.Failed(msg)
        }
        val chunks = TimedChunker.merge(cues).map { c ->
            Chunk(
                stableKey = StableKeys.audio(doc.sha256, c.startMs),
                docId = doc.id,
                module = doc.module,
                week = doc.week,
                page = null,
                startMs = c.startMs,
                text = c.text,
            )
        }
        db.ingest().replaceChunksAndMarkReady(doc.id, chunks)
        return IndexOutcome.Indexed(pages = 0, ocrPages = 0, chunks = chunks.size)
    }
}

/** An error that retrying won't fix (file gone, permission revoked, encrypted or corrupt PDF). */
class PermanentImportError(message: String, cause: Throwable? = null) : Exception(message, cause)
