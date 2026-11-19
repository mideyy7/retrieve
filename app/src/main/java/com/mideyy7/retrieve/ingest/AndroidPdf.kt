package com.mideyy7.retrieve.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Text layer via PdfBox-Android. Android's own PdfRenderer can only draw pages, not read their
 * text (on the API levels we support), which is why a separate library is needed.
 */
class PdfBoxTextSource(context: Context, input: InputStream) : PageTextSource, Closeable {
    private val document: PDDocument

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        // Spill to a temp file rather than holding large decks fully in RAM.
        document = input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
    }

    override val pageCount: Int get() = document.numberOfPages

    private val stripper = PDFTextStripper().apply { sortByPosition = true }

    override fun text(pageIndex: Int): String {
        stripper.startPage = pageIndex + 1 // PDFTextStripper pages are 1-based
        stripper.endPage = pageIndex + 1
        return stripper.getText(document)
    }

    override fun close() = document.close()
}

/** Renders pages with PdfRenderer and recognises text with ML Kit's on-device Latin model. */
class MlKitPageOcr(context: Context, uri: Uri, private val targetLongSidePx: Int = 2000) : PageOcr, Closeable {
    private val fd: ParcelFileDescriptor =
        requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "Cannot open $uri" }
    private val renderer = PdfRenderer(fd)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognise(pageIndex: Int): String {
        val bitmap = renderer.openPage(pageIndex).use { page ->
            // Page size is in points (1/72 inch). Scale so text is large enough for OCR.
            val scale = targetLongSidePx.toFloat() / max(page.width, page.height)
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(Color.WHITE) // PdfRenderer draws on transparent; OCR needs a real background
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
        renderer.close()
        fd.close()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
