package com.mideyy7.retrieve.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.mideyy7.retrieve.ui.theme.Mono
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.R
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.search.MatchedBy
import com.mideyy7.retrieve.search.SearchResult
import com.mideyy7.retrieve.search.Snippets
import com.mideyy7.retrieve.ui.components.ModuleBadge
import com.mideyy7.retrieve.ui.components.displayName
import com.mideyy7.retrieve.ui.components.highlighted
import com.mideyy7.retrieve.ui.components.locationLabel
import com.mideyy7.retrieve.ui.components.timestamp
import com.mideyy7.retrieve.ui.slides.SlideImage
import com.mideyy7.retrieve.ui.theme.moduleColor

/** What a result card can do. */
class ResultActions(
    val onOpen: (SearchResult) -> Unit,
    val onCopy: (SearchResult) -> Unit,
    val onFilter: (key: String, value: String) -> Unit,
)

/**
 * The top result, large: the slide itself next to (wide screens) or above (phones) the passage,
 * so the answer is often readable without opening anything.
 */
@Composable
fun BestMatchCard(result: SearchResult, terms: Set<String>, actions: ResultActions) {
    val c = result.hit.chunk
    val snippet = remember(c.text, terms) { Snippets.make(c.text, terms, maxChars = 360) }
    Card(
        onClick = { actions.onOpen(result) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val wide = maxWidth > 600.dp
            val preview = @Composable { m: Modifier -> Preview(result, m, large = true) }
            val text = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Best match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Breadcrumb(result, actions.onFilter)
                    Text(highlighted(snippet.text, snippet.highlights), style = MaterialTheme.typography.bodyLarge)
                    Footer(result, actions)
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    preview(Modifier.weight(1.15f))
                    text(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    preview(Modifier.fillMaxWidth())
                    text(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Every other result: a thumbnail of the slide (or the moment in the recording) beside the passage. */
@Composable
fun ResultRow(result: SearchResult, terms: Set<String>, actions: ResultActions) {
    val c = result.hit.chunk
    val snippet = remember(c.text, terms) { Snippets.make(c.text, terms, maxChars = 220) }
    Card(
        onClick = { actions.onOpen(result) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Preview(result, Modifier.width(120.dp), large = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Breadcrumb(result, actions.onFilter)
                Text(
                    highlighted(snippet.text, snippet.highlights),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Footer(result, actions)
            }
        }
    }
}

@Composable
private fun Preview(result: SearchResult, modifier: Modifier, large: Boolean) {
    val c = result.hit.chunk
    if (c.startMs != null) {
        RecordingTile(c.module, c.startMs, modifier, large)
    } else {
        SlideImage(
            c.docId, c.page ?: 1,
            modifier = modifier,
            aspect = if (large) null else 4f / 3f,
            contentDescription = "${locationLabel(result.hit.docType, c.page, null)} of ${displayName(result.hit.docName)}",
            fallback = locationLabel(result.hit.docType, c.page, null),
        )
    }
}

/** Stands in for a slide on recording results: the module's colour, a captions mark and the time. */
@Composable
private fun RecordingTile(module: String, startMs: Long, modifier: Modifier, large: Boolean) {
    val color = moduleColor(module)
    Box(
        modifier.aspectRatio(if (large) 16f / 9f else 4f / 3f).background(color.accent, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(R.drawable.ic_captions), null, tint = Color.White, modifier = Modifier.size(if (large) 32.dp else 20.dp))
            Text(
                timestamp(startMs),
                style = (if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium).copy(fontFamily = Mono, letterSpacing = 0.sp),
                color = Color.White,
            )
            if (large) Text("in the lecture recording", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** `[COMP26120] Week 6 › slide 14`. Module and week are tappable and narrow the query to them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumb(result: SearchResult, onFilter: (key: String, value: String) -> Unit) {
    val c = result.hit.chunk
    val crumb = MaterialTheme.typography.titleSmall
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        ModuleBadge(
            c.module,
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Search only ${c.module}") { onFilter("module", c.module) },
        )
        Text(
            Weeks.label(c.week),
            style = crumb,
            color = MaterialTheme.colorScheme.primary,
            // A week means this module's week, so narrow to both.
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Search only ${c.module} ${Weeks.label(c.week)}") {
                onFilter("module", c.module)
                onFilter("week", c.week.toString())
            },
        )
        Text("›", style = crumb, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(locationLabel(result.hit.docType, c.page, c.startMs), style = crumb)
    }
}

@Composable
private fun Footer(result: SearchResult, actions: ResultActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                displayName(result.hit.docName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MatchChip(result)
        }
        IconButton(onClick = { actions.onCopy(result) }) {
            Icon(painterResource(R.drawable.ic_copy), "Copy where this is", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Why this result came up, in words a student understands. */
@Composable
private fun MatchChip(r: SearchResult) {
    val (label, semantic) = when (r.matchedBy) {
        MatchedBy.KEYWORD -> "Matches your words" to false
        MatchedBy.SEMANTIC -> "Similar meaning" to true
        MatchedBy.BOTH -> "Words and meaning" to true
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (semantic) Icon(painterResource(R.drawable.ic_sparkle), null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (semantic) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
