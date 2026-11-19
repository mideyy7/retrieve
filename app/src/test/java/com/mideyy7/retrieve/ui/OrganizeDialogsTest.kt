package com.mideyy7.retrieve.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.mideyy7.retrieve.ui.components.MoveDialog
import com.mideyy7.retrieve.ui.components.TextInputDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.mideyy7.retrieve.ui.theme.RetrieveTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrganizeDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moveToAnotherModuleAsWholeModuleMaterial() {
        var moved: Pair<String, Int>? = null
        compose.setContent {
            RetrieveTheme {
                MoveDialog("Textbook.pdf", "COMP26120", 3, listOf("COMP21111", "COMP26120"), onConfirm = { m, w -> moved = m to w }, onDismiss = {})
            }
        }
        compose.onNodeWithText("COMP26120").performTextReplacement("comp2")
        compose.onNodeWithText("COMP21111").performClick() // suggestion chip
        compose.onNodeWithText("Whole module").performClick()
        compose.onNodeWithText("Move").performClick()
        assertEquals("COMP21111" to 0, moved)
    }

    @Test
    fun invalidCodeCannotBeMovedTo() {
        compose.setContent {
            RetrieveTheme { MoveDialog("a.pdf", "COMP26120", 3, emptyList(), onConfirm = { _, _ -> }, onDismiss = {}) }
        }
        compose.onNodeWithText("COMP26120").performTextReplacement("COMP 1")
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun renameWarnsBeforeMerging() {
        compose.setContent {
            RetrieveTheme {
                TextInputDialog(
                    title = "Rename", label = "Module code", initial = "COMP2612O", confirmLabel = "Rename",
                    onConfirm = {}, onDismiss = {},
                    note = { if (it.uppercase() == "COMP26120") "COMP26120 already exists: COMP2612O will be merged into it." else null },
                )
            }
        }
        compose.onNodeWithText("COMP2612O").performTextReplacement("comp26120")
        compose.onNodeWithText("COMP26120 already exists: COMP2612O will be merged into it.").assertExists()
    }
}
