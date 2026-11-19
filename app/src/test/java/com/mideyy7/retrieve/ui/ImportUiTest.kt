package com.mideyy7.retrieve.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mideyy7.retrieve.ingest.FilenameTagParser
import com.mideyy7.retrieve.ui.importer.DraftRow
import com.mideyy7.retrieve.ui.importer.ImportCallbacks
import com.mideyy7.retrieve.ui.importer.ImportContent
import com.mideyy7.retrieve.ui.importer.ImportDraft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.mideyy7.retrieve.ui.theme.RetrieveTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(name: String) = DraftRow("content://$name", name, FilenameTagParser.parse(name))

    @Test
    fun rowsFillFromDefaultsAndOnlyReadyRowsImport() {
        var draft by mutableStateOf(ImportDraft(listOf(row("L11 Raft.pdf"), row("wk7_lab.pdf"), row("notes.pdf"))))
        var imported = 0
        compose.setContent {
            RetrieveTheme {
                ImportContent(
                    draft = draft,
                    existingModules = listOf("COMP26120", "COMP21111"),
                    callbacks = ImportCallbacks(
                        onDefaultModule = { draft = draft.copy(defaultModule = it) },
                        onDefaultWeek = { draft = draft.copy(defaultWeek = it) },
                        onImport = { imported++ },
                    ),
                )
            }
        }

        compose.onNodeWithText("Import 3 files").assertExists()
        compose.onNodeWithText("Import 0").assertIsNotEnabled()

        // Picking an existing module from the chips fills every row; wk7 still needs no week, the others do.
        compose.onNodeWithText("COMP26120").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("wk7_lab.pdf"))
        compose.onNodeWithText("COMP26120 · Week 7 · Slides").assertExists()
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.onNodeWithText("Import 1").assertIsEnabled()

        // Whole module covers the rest.
        compose.onNodeWithText("Whole module").performClick()
        compose.onNodeWithText("Import").assertIsEnabled().performClick()
        assertEquals(1, imported)
        assertEquals("0", draft.defaultWeek)
    }
}
