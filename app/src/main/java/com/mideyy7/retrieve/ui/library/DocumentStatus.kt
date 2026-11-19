package com.mideyy7.retrieve.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.data.DocStatus

/** One document's indexing state in words, with progress while indexing and Retry when it failed. */
@Composable
fun DocumentStatus(row: DocumentRow, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val d = row.summary
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (d.status) {
            DocStatus.READY -> Text("${d.type.label()} · ${d.chunkCount} chunk${if (d.chunkCount == 1) "" else "s"}", style = small, color = muted)
            DocStatus.PENDING -> Text("${d.type.label()} · Queued", style = small, color = muted)
            DocStatus.INDEXING -> {
                val p = row.progress
                if (p != null && p.total > 0) {
                    Text("Reading page ${p.done} of ${p.total}", style = small, color = muted)
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Indexing", style = small, color = muted)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            DocStatus.FAILED -> {
                Text("Failed: ${d.error ?: "unknown error"}", style = small, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
