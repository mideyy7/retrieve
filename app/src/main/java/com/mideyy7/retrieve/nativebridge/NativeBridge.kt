package com.mideyy7.retrieve.nativebridge

/**
 * Kotlin side of the JNI boundary. Each `external fun` is implemented in native/jni/.
 * Loading the library happens once, the first time this object is touched.
 *
 * Index handles are raw native pointers: use them only through [com.mideyy7.retrieve.search.KeywordIndex],
 * which guarantees each handle is closed exactly once and never used after closing.
 */
object NativeBridge {
    init {
        System.loadLibrary("retrieve")
    }

    external fun nativeVersion(): String

    /** Writes an index file atomically. Arrays are parallel (one entry per chunk). Returns null or an error. */
    external fun buildIndex(
        path: String,
        stableKeys: Array<String>,
        modules: Array<String>,
        weeks: IntArray,
        types: IntArray,
        pages: IntArray,
        startMs: LongArray,
        texts: Array<String>,
    ): String?

    /** Memory-maps an index file. Throws IOException if it is missing or corrupt. */
    external fun openIndex(path: String): Long

    external fun closeIndex(handle: Long)

    /** [numDocs, numTerms, fileSizeBytes] */
    external fun indexStats(handle: Long): LongArray

    /** Runs a query-language search (docs/query-language.md). Throws [QuerySyntaxException] for invalid syntax. */
    external fun search(handle: Long, query: String, k: Int): NativeHits
}

/** Search results as returned from native code: parallel arrays, best first. Constructed from JNI. */
class NativeHits(val stableKeys: Array<String>, val scores: FloatArray)

/** Thrown from native code for an invalid query; the message includes the 1-based column. */
class QuerySyntaxException(message: String) : IllegalArgumentException(message)
