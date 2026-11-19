package com.mideyy7.retrieve.ui.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mideyy7.retrieve.data.Chunk
import com.mideyy7.retrieve.data.ChunkWithDoc
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.search.MatchedBy
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.ui.theme.RetrieveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun result(page: Int?, startMs: Long?, type: DocType, matchedBy: MatchedBy = MatchedBy.KEYWORD) = SearchResult(
        ChunkWithDoc(
            Chunk(id = 1, stableKey = "k", docId = 99, module = "COMP21111", week = 4, page = page, startMs = startMs,
                text = "Unit propagation assigns the only literal left in a unit clause."),
            docName = if (type == DocType.AUDIO) "W4 L1.vtt" else "Lecture 4 SAT.pdf",
            docType = type,
            docSha = "abc",
        ),
        score = 3.2f,
        matchedBy = matchedBy,
    )

    @Test
    fun bestMatchShowsWhereItIsAndOpensAndFilters() {
        val opened = mutableListOf<SearchResult>()
        val filters = mutableListOf<Pair<String, String>>()
        val r = result(22, null, DocType.SLIDES, MatchedBy.BOTH)
        compose.setContent {
            RetrieveTheme {
                BestMatchCard(r, setOf("unit"), ResultActions(onOpen = { opened += it }, onCopy = {}, onFilter = { k, v -> filters += k to v }))
            }
        }
        compose.onNodeWithText("Best match").assertExists()
        compose.onNodeWithText("slide 22").assertExists()
        compose.onNodeWithText("Lecture 4 SAT").assertExists()
        compose.onNodeWithText("Words and meaning").assertExists()

        compose.onNodeWithText("Week 4").performClick()
        assertEquals(listOf("module" to "COMP21111", "week" to "4"), filters)
        compose.onNodeWithText("slide 22").performClick()
        assertEquals(listOf(r), opened)
    }

    @Test
    fun recordingResultShowsTheTimestamp() {
        var copied: SearchResult? = null
        val r = result(null, 2_633_000, DocType.AUDIO)
        compose.setContent {
            RetrieveTheme { ResultRow(r, setOf("unit"), ResultActions(onOpen = {}, onCopy = { copied = it }, onFilter = { _, _ -> })) }
        }
        // Once on the tile, once in the breadcrumb.
        compose.onAllNodesWithText("43:53", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Matches your words").assertExists()
        compose.onNodeWithContentDescription("Copy where this is").performClick()
        assertEquals(r, copied)
    }
}
