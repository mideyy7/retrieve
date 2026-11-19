package com.mideyy7.retrieve.ingest

/** Text of one PDF page. [page] is 1-based; [ocr] records whether OCR produced the text. */
data class ExtractedPage(val page: Int, val text: String, val ocr: Boolean)

/** Source of a PDF's embedded text layer, one page at a time (0-based index). */
interface PageTextSource {
    val pageCount: Int
    fun text(pageIndex: Int): String
}

/** Renders a page and runs text recognition on it (0-based index). */
fun interface PageOcr {
    suspend fun recognise(pageIndex: Int): String
}

/**
 * Per-page extraction with an OCR fallback (spec §5.1). A page whose text layer has fewer than
 * [minChars] non-whitespace characters is treated as an image (scanned handout, slide exported
 * as a picture) and sent to OCR. If OCR finds less than the text layer had, the text layer wins.
 */
class PdfTextExtractor(private val minChars: Int = 20) {

    suspend fun extract(
        source: PageTextSource,
        ocr: PageOcr,
        onPage: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ExtractedPage> {
        val total = source.pageCount
        return (0 until total).map { i ->
            val layer = source.text(i)
            val page = if (meaningfulChars(layer) >= minChars) {
                ExtractedPage(i + 1, layer, ocr = false)
            } else {
                val recognised = ocr.recognise(i)
                if (meaningfulChars(recognised) > meaningfulChars(layer)) {
                    ExtractedPage(i + 1, recognised, ocr = true)
                } else {
                    ExtractedPage(i + 1, layer, ocr = false)
                }
            }
            onPage(i + 1, total)
            page
        }
    }

    private fun meaningfulChars(s: String) = s.count { !it.isWhitespace() }
}
