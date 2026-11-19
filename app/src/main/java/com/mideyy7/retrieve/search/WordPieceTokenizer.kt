package com.mideyy7.retrieve.search

import java.text.Normalizer
import java.util.Locale

/**
 * BERT WordPiece tokenizer that reproduces HuggingFace `tokenizers` (BertNormalizer with
 * lowercase = true and strip_accents unset, BertPreTokenizer, WordPiece with "##" continuations and
 * 100-char words, [CLS] … [SEP] with right truncation). The embedding model only works if the app
 * feeds it exactly the ids it was trained and exported with; WordPieceTokenizerTest checks this
 * against ids produced by HuggingFace for 454 inputs.
 *
 * Places where Java's defaults differ from the Rust implementation, handled explicitly:
 *  - "whitespace" is the Unicode White_Space property (includes U+00A0 and U+202F, unlike
 *    Character.isWhitespace);
 *  - lowercasing is per code point without context ("İ" -> "i̇", no final-sigma rule);
 *  - control/format/private-use/unassigned characters are dropped (e.g. U+200B, U+00AD).
 */
class WordPieceTokenizer(vocab: List<String>, private val maxLen: Int = 128) {
    private val ids: Map<String, Int> = HashMap<String, Int>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, token -> putIfAbsent(token, i) }
    }
    private val unk = id("[UNK]")
    val cls = id("[CLS]")
    val sep = id("[SEP]")
    val pad = id("[PAD]")

    private fun id(token: String) = requireNotNull(ids[token]) { "vocabulary has no $token" }

    /** Token ids with [CLS] and [SEP], truncated to maxLen. Not padded. */
    fun encode(text: String): IntArray {
        val pieces = ArrayList<Int>()
        for (word in preTokenize(normalize(text))) wordPiece(word, pieces)
        val keep = minOf(pieces.size, maxLen - 2)
        val out = IntArray(keep + 2)
        out[0] = cls
        for (i in 0 until keep) out[i + 1] = pieces[i]
        out[keep + 1] = sep
        return out
    }

    /** Ids padded with [PAD] to maxLen, and the matching attention mask, as the model expects. */
    fun encodePadded(text: String): Pair<IntArray, IntArray> {
        val ids = encode(text)
        val input = IntArray(maxLen) { if (it < ids.size) ids[it] else pad }
        val mask = IntArray(maxLen) { if (it < ids.size) 1 else 0 }
        return input to mask
    }

    // ---- BertNormalizer: clean text, pad CJK, strip accents, lowercase ------------------------

    private fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length + 8)
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isChinese(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val stripped = StringBuilder(cleaned.length)
        Normalizer.normalize(cleaned, Normalizer.Form.NFD).codePoints().forEach { cp ->
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) stripped.appendCodePoint(cp)
        }
        val lowered = StringBuilder(stripped.length)
        stripped.codePoints().forEach { cp -> lowered.append(String(Character.toChars(cp)).lowercase(Locale.ROOT)) }
        return lowered.toString()
    }

    // ---- BertPreTokenizer: split on whitespace, isolate punctuation ----------------------------

    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words.add(current.toString())
            current.setLength(0)
        }
        text.codePoints().forEach { cp ->
            when {
                isWhitespace(cp) -> flush()
                isPunctuation(cp) -> {
                    flush()
                    words.add(String(Character.toChars(cp)))
                }
                else -> current.appendCodePoint(cp)
            }
        }
        flush()
        return words
    }

    // ---- WordPiece: greedy longest match, "##" for continuations -------------------------------

    private fun wordPiece(word: String, out: MutableList<Int>) {
        val cps = word.codePoints().toArray()
        if (cps.size > MAX_WORD_CHARS) {
            out.add(unk)
            return
        }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var found = -1
            while (start < end) {
                val sub = String(cps, start, end - start)
                val candidate = ids[if (start > 0) "##$sub" else sub]
                if (candidate != null) {
                    found = candidate
                    break
                }
                end--
            }
            if (found < 0) {
                out.add(unk) // any unmatched piece makes the whole word [UNK]
                return
            }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
    }

    private companion object {
        const val MAX_WORD_CHARS = 100

        // Unicode White_Space property (what Rust's char::is_whitespace uses).
        fun isWhitespace(cp: Int) = cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x20 ||
            cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 ||
            cp == 0x202F || cp == 0x205F || cp == 0x3000

        // Unicode "Other" categories, except \t \n \r.
        fun isControl(cp: Int): Boolean {
            if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return false
            return when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
                Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt() -> true
                else -> false
            }
        }

        fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        // CJK ideographs that BERT splits into single characters.
        fun isChinese(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF ||
            cp in 0x2F800..0x2FA1F
    }
}
