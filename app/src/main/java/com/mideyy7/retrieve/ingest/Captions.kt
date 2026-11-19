package com.mideyy7.retrieve.ingest

/** One timed piece of a lecture transcript (a caption cue, or later a Whisper segment). */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** A search chunk of a recording: ~30 s of speech starting at [startMs]. */
data class TimedChunk(val startMs: Long, val endMs: Long, val text: String)

/**
 * WebVTT parser for lecture captions exported by the university video portal (spec §5.6).
 * Handles: the WEBVTT header, optional cue identifiers, `mm:ss.ttt` and `hh:mm:ss.ttt` timings with
 * cue settings after them, multi-line cue text, NOTE/STYLE/REGION blocks, voice/class/timestamp tags
 * (`<v Speaker>`, `<c.x>`, `<00:01.000>`), and the common HTML entities.
 */
object WebVtt {
    private val TIMING = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")
    private val TAG = Regex("<[^>]*>")

    fun parse(content: String): List<Cue> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val block = line.trim()
            if (block.startsWith("NOTE") || block == "STYLE" || block == "REGION" || block.startsWith("WEBVTT")) {
                while (i < lines.size && lines[i].isNotBlank()) i++ // skip the whole block
                continue
            }
            // A cue may have an identifier line before its timing line.
            val timingLine = when {
                TIMING.containsMatchIn(line) -> i
                i + 1 < lines.size && TIMING.containsMatchIn(lines[i + 1]) && line.isNotBlank() -> i + 1
                else -> -1
            }
            if (timingLine < 0) {
                i++
                continue
            }
            val m = TIMING.find(lines[timingLine])!!
            val start = parseTime(m.groupValues[1])
            val end = parseTime(m.groupValues[2])
            i = timingLine + 1
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(lines[i].trim())
                i++
            }
            val clean = decode(TAG.replace(text, "")).replace(Regex("\\s+"), " ").trim()
            if (start != null && end != null && clean.isNotEmpty()) cues.add(Cue(start, end, clean))
        }
        return cues
    }

    /** `mm:ss.ttt` or `hh:mm:ss.ttt` (also accepts ',' as the millisecond separator). */
    fun parseTime(s: String): Long? {
        val parts = s.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val secParts = parts.last().split('.')
        val sec = secParts[0].toLongOrNull() ?: return null
        val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val min = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + min) * 60 + sec) * 1000 + ms
    }

    private fun decode(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * Merges consecutive cues into chunks of about [targetMs] of speech (spec §5.6: ~30 s). A chunk is
 * closed once it spans at least [targetMs]; a final remainder shorter than [minTailMs] is folded
 * into the previous chunk so no chunk is a few seconds of filler. ~30 s of lecture speech is
 * ~80–100 words, which fits the embedding model's 128-token input without truncation.
 */
object TimedChunker {
    fun merge(cues: List<Cue>, targetMs: Long = 30_000, minTailMs: Long = 10_000): List<TimedChunk> {
        val chunks = ArrayList<TimedChunk>()
        var start = -1L
        var end = -1L
        val text = StringBuilder()
        fun close() {
            if (start >= 0 && text.isNotBlank()) chunks.add(TimedChunk(start, end, text.toString()))
            start = -1L
            text.setLength(0)
        }
        for (cue in cues.sortedBy { it.startMs }) {
            if (start < 0) start = cue.startMs
            if (text.isNotEmpty()) text.append(' ')
            text.append(cue.text)
            end = maxOf(end, cue.endMs)
            if (end - start >= targetMs) close()
        }
        if (start >= 0 && text.isNotBlank()) {
            val tail = TimedChunk(start, end, text.toString())
            if (chunks.isNotEmpty() && tail.endMs - tail.startMs < minTailMs) {
                val last = chunks.removeAt(chunks.size - 1)
                chunks.add(TimedChunk(last.startMs, tail.endMs, last.text + " " + tail.text))
            } else {
                chunks.add(tail)
            }
        }
        return chunks
    }
}
