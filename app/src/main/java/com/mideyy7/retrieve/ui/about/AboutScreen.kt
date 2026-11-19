package com.mideyy7.retrieve.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.ui.theme.Mono

/** A third-party component the app ships, its licence and where the full text is. */
data class Notice(val name: String, val use: String, val licence: String, val url: String, val asset: String? = null)

/** Everything the app ships that isn't ours. Gemma's notice is required by the Gemma Terms of Use. */
val NOTICES = listOf(
    Notice(
        "Gemma 3 1B (Google)", "Writes the short answers in Ask, on the device.",
        "Gemma is provided under and subject to the Gemma Terms of Use found at ai.google.dev/gemma/terms, " +
            "including the Gemma Prohibited Use Policy.",
        "https://ai.google.dev/gemma/terms",
    ),
    Notice(
        "all-MiniLM-L6-v2 (sentence-transformers)", "Teacher of the app's search-by-meaning model, which was distilled from it.",
        "Apache License 2.0", "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2",
    ),
    Notice("LiteRT and LiteRT-LM (Google)", "Run the embedding model and Gemma on the device.", "Apache License 2.0", "https://github.com/google-ai-edge/LiteRT"),
    Notice("PdfBox-Android", "Reads the text of imported PDFs.", "Apache License 2.0", "https://github.com/TomRoush/PdfBox-Android"),
    Notice("ML Kit Text Recognition (Google)", "Reads slides that are images (OCR), on the device.", "ML Kit Terms of Service", "https://developers.google.com/ml-kit/terms"),
    Notice("AndroidX, Jetpack Compose, Room, WorkManager", "The app framework.", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Notice("Bricolage Grotesque", "Headings.", "SIL Open Font License 1.1", "https://fonts.google.com/specimen/Bricolage+Grotesque", "licenses/OFL-BricolageGrotesque.txt"),
    Notice("Instrument Sans", "Body text.", "SIL Open Font License 1.1", "https://fonts.google.com/specimen/Instrument+Sans", "licenses/OFL-InstrumentSans.txt"),
    Notice("JetBrains Mono", "Queries and timestamps.", "SIL Open Font License 1.1", "https://www.jetbrains.com/lp/mono/", "licenses/OFL-JetBrainsMono.txt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("About & licences") },
            )
        },
    ) { padding ->
        val context = LocalContext.current
        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Retrieve $version", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Everything runs on this device. Retrieve has no account, no server and no internet permission: " +
                        "your files, searches and questions never leave the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Open-source and third-party components", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                NOTICES.forEach { NoticeCard(it) }
            }
        }
    }
}

@Composable
private fun NoticeCard(n: Notice) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var showText by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(n.licence, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri(n.url) } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(n.url, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono))
            }
            if (n.asset != null) {
                TextButton(onClick = { showText = !showText }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (showText) "Hide licence text" else "Show licence text")
                }
                if (showText) {
                    val text = remember(n.asset) { runCatching { context.assets.open(n.asset).bufferedReader().readText() }.getOrDefault("") }
                    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
