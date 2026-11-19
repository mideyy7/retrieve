package com.mideyy7.retrieve.ingest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Runs the real PdfBox text layer on a PDF generated in the test (one text page, one blank page). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfBoxTextSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun makePdf(vararg pageTexts: String?): ByteArray {
        PDFBoxResourceLoader.init(context)
        return PDDocument().use { doc ->
            for (text in pageTexts) {
                val page = PDPage()
                doc.addPage(page)
                if (text != null) {
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 14f)
                        cs.newLineAtOffset(72f, 700f)
                        cs.showText(text)
                        cs.endText()
                    }
                }
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    @Test
    fun extractsTextPerPageAndBlankPageIsEmpty() {
        val pdf = makePdf("Raft log matching ensures consistency", null, "Amortised analysis")
        PdfBoxTextSource(context, ByteArrayInputStream(pdf)).use { src ->
            assertEquals(3, src.pageCount)
            assertTrue(src.text(0).contains("Raft log matching ensures consistency"))
            assertEquals("", src.text(1).trim())
            assertTrue(src.text(2).contains("Amortised analysis"))
            assertTrue("page 3 must not leak into page 1", !src.text(0).contains("Amortised"))
        }
    }
}
