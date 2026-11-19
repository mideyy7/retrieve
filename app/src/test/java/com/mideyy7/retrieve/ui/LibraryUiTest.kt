package com.mideyy7.retrieve.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mideyy7.retrieve.data.DocStatus
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.DocumentSummary
import com.mideyy7.retrieve.data.ModuleSummary
import com.mideyy7.retrieve.ui.library.DocumentRow
import com.mideyy7.retrieve.ui.library.ModuleCard
import com.mideyy7.retrieve.ui.library.ModuleGrid
import com.mideyy7.retrieve.ui.library.SegmentState
import com.mideyy7.retrieve.ui.library.WeekSegment
import com.mideyy7.retrieve.ui.module.ModuleContent
import com.mideyy7.retrieve.ui.module.RowAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.mideyy7.retrieve.ui.theme.RetrieveTheme

/** Renders the stateless library screens on the JVM and drives them like a user would. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moduleGridOpensAModule() {
        var opened: String? = null
        val card = ModuleCard(
            ModuleSummary("COMP26120", "Algorithms", 3, 40, 2, 1, 0, 0),
            listOf(WeekSegment(1, SegmentState.READY), WeekSegment(2, SegmentState.FAILED)),
        )
        compose.setContent { RetrieveTheme { ModuleGrid(listOf(card), onOpenModule = { opened = it }) } }

        compose.onNodeWithContentDescription("COMP26120, Algorithms, 3 documents, 2 weeks, 1 failed").performClick()
        assertEquals("COMP26120", opened)
    }

    @Test
    fun emptyLibrarySaysWhatToDo() {
        compose.setContent { RetrieveTheme { ModuleGrid(emptyList(), onOpenModule = {}) } }
        compose.onNodeWithText("No modules yet").assertExists()
    }

    private fun rows(n: Int) = (1..n).map { i ->
        DocumentRow(
            DocumentSummary(i.toLong(), "Lecture $i.pdf", "COMP26120", week = (i + 1) / 2, DocType.SLIDES,
                if (i == 3) DocStatus.FAILED else DocStatus.READY, if (i == 3) "Couldn't read page 4" else null, 10),
            progress = null,
        )
    }

    @Test
    fun modulePageGroupsByWeekSearchesAndRetries() {
        var searched: String? = null
        val actions = mutableListOf<Pair<Long, RowAction>>()
        compose.setContent {
            RetrieveTheme {
                ModuleContent(
                    code = "COMP26120", rows = rows(4), filter = "", toggled = emptyMap(),
                    onFilterChange = {}, onToggleWeek = { _, _ -> }, onSearch = { searched = it },
                    onWeekAction = { _, _ -> }, onRowAction = { row, a -> actions += row.summary.id to a },
                )
            }
        }

        compose.onNodeWithText("4 docs · weeks 1–2 · 40 chunks").assertExists()
        compose.onNodeWithText("Week 1").assertExists()
        compose.onNodeWithText("Week 2").assertExists()
        // Short module: every week open. The list is lazy, so scroll the last file into view first.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Lecture 4.pdf"))
        compose.onNodeWithText("Lecture 4.pdf").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(0)

        compose.onNodeWithText("Search COMP26120").performClick()
        assertEquals("module:COMP26120 ", searched)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Retry"))
        compose.onNodeWithText("Failed: Couldn't read page 4").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(3L to RowAction.RETRY), actions)
    }

    @Test
    fun longModulesStartFoldedAndShowAFilter() {
        var filter = ""
        compose.setContent {
            RetrieveTheme {
                ModuleContent(
                    code = "COMP26120", rows = rows(14), filter = "", toggled = emptyMap(),
                    onFilterChange = { filter = it }, onToggleWeek = { _, _ -> }, onSearch = {},
                    onWeekAction = { _, _ -> }, onRowAction = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Week 1").assertExists()
        compose.onAllNodesWithText("Lecture 1.pdf").assertCountEquals(0)
        compose.onNodeWithText("Find a file").performTextInput("raft")
        assertEquals("raft", filter)
    }
}
