package com.mideyy7.retrieve.ui.slides

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.RetrieveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Draws pages of imported PDFs so results can show the slide itself, not just its text.
 *
 * Reads the original file through its content:// URI (nothing is copied at import). PdfRenderer
 * allows one open page at a time, so all rendering goes through one lock, keeping the most recently
 * used document open because results usually come from a handful of files. Rendered pages are kept
 * in a memory cache keyed by document, page and width.
 */
class SlideRenderer(private val context: Context, private val db: RetrieveDatabase) {
    private val lock = Mutex()
    private var open: OpenDoc? = null

    private class OpenDoc(val docId: Long, val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            renderer.close()
            fd.close()
        }
    }

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Number of pages, or null if the file can't be opened (moved, deleted, permission gone) or isn't a PDF. */
    suspend fun pageCount(docId: Long): Int? = withRenderer(docId) { it.pageCount }

    /** Page [page] (1-based) drawn [widthPx] wide on white, or null if it can't be drawn. */
    suspend fun render(docId: Long, page: Int, widthPx: Int): Bitmap? {
        val width = widthPx.coerceIn(64, MAX_WIDTH)
        val key = "$docId:$page:$width"
        cache.get(key)?.let { return it }
        return withRenderer(docId) { renderer ->
            if (page !in 1..renderer.pageCount) return@withRenderer null
            renderer.openPage(page - 1).use { p ->
                val height = (width.toLong() * p.height / p.width).toInt().coerceIn(1, MAX_WIDTH * 2)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                    bmp.eraseColor(Color.WHITE) // PdfRenderer leaves the background transparent
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }?.also { cache.put(key, it) }
    }

    private suspend fun <T> withRenderer(docId: Long, block: (PdfRenderer) -> T?): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val doc = open?.takeIf { it.docId == docId } ?: openDoc(docId)?.also {
                    open?.close()
                    open = it
                } ?: return@withLock null
                block(doc.renderer)
            } catch (e: Exception) {
                // A broken or revoked file only loses its preview; the text result still shows.
                Log.w(TAG, "can't render document $docId: ${e.message}")
                null
            }
        }
    }

    private suspend fun openDoc(docId: Long): OpenDoc? {
        val doc = db.documents().byId(docId) ?: return null
        if (doc.type == DocType.AUDIO) return null
        val fd = context.contentResolver.openFileDescriptor(Uri.parse(doc.sourceUri), "r") ?: return null
        return try {
            OpenDoc(docId, fd, PdfRenderer(fd))
        } catch (e: Exception) {
            fd.close()
            throw e
        }
    }

    private companion object {
        const val TAG = "SlideRenderer"
        const val CACHE_BYTES = 32 * 1024 * 1024
        const val MAX_WIDTH = 2048
    }
}
