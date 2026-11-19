package com.mideyy7.retrieve.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DocType { SLIDES, PDF, AUDIO }

/**
 * Lifecycle of an import. Chunks are only committed together with the switch to READY (in one
 * transaction), so a document that isn't READY never has a partial set of chunks.
 */
enum class DocStatus { PENDING, INDEXING, READY, FAILED }

@Entity(
    tableName = "documents",
    indices = [Index(value = ["sha256"], unique = true)],
)
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SHA-256 of the file bytes, hex. Same file imported twice = same document. */
    val sha256: String,
    val name: String,
    val module: String,
    val week: Int,
    val type: DocType,
    val status: DocStatus = DocStatus.PENDING,
    /** content:// URI the file was imported from (SAF). */
    val sourceUri: String,
    val importedAt: Long,
    val error: String? = null,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["stableKey"], unique = true),
        Index(value = ["docId"]),
        Index(value = ["module", "week"]),
    ],
)
data class Chunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identity that survives reinstalls and re-imports; see [StableKeys]. The only id that leaves the app. */
    val stableKey: String,
    val docId: Long,
    val module: String,
    val week: Int,
    /** 1-based page/slide number for PDFs, null for audio. */
    val page: Int?,
    /** Start of the chunk in the recording, null for PDFs. */
    val startMs: Long?,
    val text: String,
    /** True if the page's text came from OCR rather than the PDF's text layer (added in schema v2). */
    @ColumnInfo(defaultValue = "0") val ocr: Boolean = false,
    /** float32 little-endian embedding, filled in Phase 5. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
) {
    // ByteArray uses identity equality, so data-class equals would be wrong without these.
    override fun equals(other: Any?): Boolean =
        other is Chunk && id == other.id && stableKey == other.stableKey && docId == other.docId &&
            module == other.module && week == other.week && page == other.page &&
            startMs == other.startMs && text == other.text && ocr == other.ocr &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))

    override fun hashCode(): Int = stableKey.hashCode()
}

/** Row type for the library screen. */
data class DocumentSummary(
    val id: Long,
    val name: String,
    val module: String,
    val week: Int,
    val type: DocType,
    val status: DocStatus,
    val error: String?,
    val chunkCount: Int,
)

object StableKeys {
    private fun prefix(sha256: String) = sha256.take(12)

    fun page(sha256: String, page: Int, index: Int) = "${prefix(sha256)}:p$page:$index"

    fun audio(sha256: String, startMs: Long) = "${prefix(sha256)}:t$startMs"
}
