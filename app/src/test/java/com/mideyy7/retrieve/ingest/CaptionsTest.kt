package com.mideyy7.retrieve.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsTest {
    @Test
    fun parsesPortalStyleCaptions() {
        // Same shape as the COMP23311 export: no identifiers, mm:ss.ttt, a cue wrapping onto two lines.
        val vtt = """
            WEBVTT

            00:01.140 --> 00:02.520
            Okay, good morning

            00:09.160 --> 00:14.490
            Come to three three one one week three session two and we are going to talk
            about
        """.trimIndent()
        assertEquals(
            listOf(
                Cue(1_140, 2_520, "Okay, good morning"),
                Cue(9_160, 14_490, "Come to three three one one week three session two and we are going to talk about"),
            ),
            WebVtt.parse(vtt),
        )
    }

    @Test
    fun handlesIdentifiersHoursSettingsNotesTagsAndEntities() {
        val vtt = "﻿WEBVTT - lecture\r\nKind: captions\r\n\r\nNOTE produced by the portal\r\nsecond note line\r\n\r\n" +
            "STYLE\r\n::cue { color: red }\r\n\r\n" +
            "cue-7\r\n01:02:03.004 --> 01:02:05.500 align:start position:10%\r\n<v Tom>Unit &amp; <c.loud>integration</c></v> tests\r\n\r\n"
        assertEquals(listOf(Cue(3_723_004, 3_725_500, "Unit & integration tests")), WebVtt.parse(vtt))
    }

    @Test
    fun skipsEmptyAndMalformedCues() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\n\n\n00:xx.000 --> 00:03.000\nbad time\n\n00:04.000 --> 00:05.000\n<i></i>\n"
        assertTrue(WebVtt.parse(vtt).isEmpty())
    }

    @Test
    fun parseTimeVariants() {
        assertEquals(65_500L, WebVtt.parseTime("01:05.5"))
        assertEquals(3_600_000L, WebVtt.parseTime("1:00:00.000"))
        assertEquals(2_250L, WebVtt.parseTime("00:02,250"))
        assertNull(WebVtt.parseTime("12"))
    }

    @Test
    fun mergesIntoThirtySecondChunks() {
        val cues = (0 until 20).map { Cue(it * 5_000L, it * 5_000L + 4_000, "c$it") } // 5 s apart, ~100 s total
        val chunks = TimedChunker.merge(cues)
        // A chunk closes on the cue that takes it past 30 s (the 30 s cue ends at 34 s), so the next starts at 35 s.
        assertEquals(listOf(0L, 35_000L, 70_000L), chunks.map { it.startMs })
        assertTrue(chunks.dropLast(1).all { it.endMs - it.startMs >= 30_000 })
        assertEquals(cues.joinToString(" ") { it.text }, chunks.joinToString(" ") { it.text }) // nothing lost or reordered
    }

    @Test
    fun shortTailIsFoldedIntoThePreviousChunk() {
        val cues = listOf(Cue(0, 31_000, "long"), Cue(31_500, 34_000, "tail"))
        assertEquals(listOf(TimedChunk(0, 34_000, "long tail")), TimedChunker.merge(cues))
    }

    @Test
    fun singleShortRecordingIsOneChunk() {
        assertEquals(listOf(TimedChunk(1_000, 5_000, "hi there")), TimedChunker.merge(listOf(Cue(1_000, 3_000, "hi"), Cue(3_000, 5_000, "there"))))
        assertTrue(TimedChunker.merge(emptyList()).isEmpty())
    }
}
