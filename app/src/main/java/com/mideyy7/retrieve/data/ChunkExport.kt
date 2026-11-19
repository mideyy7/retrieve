package com.mideyy7.retrieve.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app -> ml/ hand-off format (ml/data/chunks.json). Deterministic: chunks sorted by
 * stableKey and no timestamps, so exporting the same library twice gives byte-identical files.
 * Test-set labels refer to (docSha, page) or (docSha, time range), which every chunk carries.
 */
@Serializable
data class ChunkExport(
    @SerialName("format_version") val formatVersion: Int = 1,
    @SerialName("app_version") val appVersion: String,
    val chunks: List<ExportedChunk>,
)

@Serializable
data class ExportedChunk(
    @SerialName("stable_key") val stableKey: String,
    @SerialName("doc_sha256") val docSha256: String,
    @SerialName("doc_name") val docName: String,
    val type: String,
    val module: String,
    val week: Int,
    val page: Int?,
    @SerialName("start_ms") val startMs: Long?,
    val text: String,
)

object ChunkExporter {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun toJson(appVersion: String, documents: List<Document>, chunks: List<Chunk>): String {
        val docs = documents.associateBy { it.id }
        val exported = chunks.sortedBy { it.stableKey }.map { c ->
            val d = requireNotNull(docs[c.docId]) { "chunk ${c.stableKey} has no document" }
            ExportedChunk(c.stableKey, d.sha256, d.name, d.type.name, c.module, c.week, c.page, c.startMs, c.text)
        }
        return json.encodeToString(ChunkExport.serializer(), ChunkExport(appVersion = appVersion, chunks = exported)) + "\n"
    }
}
