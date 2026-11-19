package com.mideyy7.retrieve.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mideyy7.retrieve.ui.theme.Mono
import androidx.compose.ui.unit.dp

/** One line of the search tips: what to type, and what it does. */
data class SearchTip(val query: String, val meaning: String)

/** Examples use one of the student's own modules so they work when tapped. */
fun searchTips(module: String?): List<SearchTip> {
    val m = module ?: "COMP21111"
    return listOf(
        SearchTip("what is unit propagation", "Plain words or a whole question. Finds matching words and passages with a similar meaning."),
        SearchTip("\"stored procedure\"", "Quotes: these words together, in this order."),
        SearchTip("module:$m", "Only this module. Digits alone work too: module:${m.takeLast(5)}."),
        SearchTip("module:$m week:4..6", "Only weeks 4 to 6. week:0 is whole-module material, like a textbook."),
        SearchTip("type:slides", "Only slides. Also type:pdf, and type:audio for recording transcripts."),
        SearchTip("raft OR paxos", "Either word."),
        SearchTip("trigger AND update", "Both words."),
        SearchTip("index NOT tutorial", "Leave out anything that mentions tutorial."),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHelpSheet(module: String?, onTry: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Search tips", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Type the way you'd ask a friend. To narrow things down, mix in any of these. Tap one to try it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            searchTips(module).forEach { tip ->
                Column(
                    Modifier.fillMaxWidth().clickable { onTry(tip.query) }.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        tip.query,
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(tip.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "AND, OR and NOT only count in capitals. Everything runs on this device; nothing you search leaves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
