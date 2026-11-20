package com.mideyy7.retrieve.ui.ask

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.R
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.qa.KeySentence
import com.mideyy7.retrieve.qa.LlmModel
import com.mideyy7.retrieve.qa.ModelAvailability
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import com.mideyy7.retrieve.qa.RenderedAnswer
import com.mideyy7.retrieve.qa.Source
import com.mideyy7.retrieve.qa.render
import com.mideyy7.retrieve.search.Snippets
import com.mideyy7.retrieve.ui.components.ModuleBadge
import com.mideyy7.retrieve.ui.components.displayName
import com.mideyy7.retrieve.ui.components.highlighted
import com.mideyy7.retrieve.ui.components.locationLabel
import com.mideyy7.retrieve.ui.components.timestamp
import com.mideyy7.retrieve.ui.slides.SlideImage
import com.mideyy7.retrieve.ui.theme.Mono
import com.mideyy7.retrieve.ui.theme.moduleColor
import androidx.compose.ui.graphics.Color

private val MaxContentWidth = 840.dp

/**
 * Ask a question, get a short answer written on the device from the student's own passages, with
 * every claim pointing at the slide or recording moment it came from.
 */
@Composable
fun AskScreen(
    onOpenSource: (Source, question: String) -> Unit,
    onSearchInstead: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: AskViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    // Play's "download 580 MB over mobile data?" dialog; the download carries on once accepted.
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    DisposableEffect(vm) {
        vm.onScreenShown()
        onDispose { vm.onScreenHidden() }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = MaxContentWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_sparkle), null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("Ask your lectures", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "Answers come only from your slides and recordings, written on this device, with a link to every source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QuestionField(
                value = state.question,
                busy = state.busy,
                onChange = vm::onQuestionChange,
                onAsk = {
                    keyboard?.hide()
                    vm.ask()
                },
                onStop = vm::stop,
            )
            if (model !is ModelAvailability.Ready) {
                ModelCard(model, onDownload = vm::downloadModel, onConfirm = { LlmModel.confirm(context, confirm) })
            }
            if (state.phase != AskPhase.IDLE) AnswerCard(state, onOpenSource = { onOpenSource(it, state.question) }, onSearchInstead = onSearchInstead)
        }
    }
}

/** The answering model isn't on the phone yet: offer the one-off download, then show its progress. */
@Composable
private fun ModelCard(model: ModelAvailability, onDownload: () -> Unit, onConfirm: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("One-time download for Ask", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Ask writes answers with Gemma, a language model that runs on your phone (about 580 MB). " +
                    "After this download it works offline. Search doesn't need it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            when (model) {
                is ModelAvailability.Downloadable -> Button(onClick = onDownload) { Text("Download (${mb(model.sizeBytes ?: LlmModel.SIZE_BYTES)})") }
                is ModelAvailability.Downloading -> {
                    LinearProgressIndicator(progress = { if (model.total > 0) model.bytes.toFloat() / model.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("${mb(model.bytes)} of ${mb(model.total)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                ModelAvailability.NeedsConfirmation -> {
                    Text("It's large, so Play asks before using mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = onConfirm) { Text("Continue") }
                }
                is ModelAvailability.Unavailable -> Text(model.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ModelAvailability.Ready -> Unit
            }
        }
    }
}

private fun mb(bytes: Long) = "${bytes / 1_000_000} MB"

@Composable
private fun QuestionField(value: String, busy: Boolean, onChange: (String) -> Unit, onAsk: () -> Unit, onStop: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        placeholder = { Text("e.g. what does unit propagation do?") },
        maxLines = 4,
        trailingIcon = {
            if (busy) {
                IconButton(onClick = onStop) { Icon(Icons.Filled.Close, contentDescription = "Stop") }
            } else {
                IconButton(onClick = onAsk, enabled = value.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onAsk() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@Composable
private fun AnswerCard(state: AskUiState, onOpenSource: (Source) -> Unit, onSearchInstead: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.phase) {
                AskPhase.REFUSED -> Refused(state, onSearchInstead)
                AskPhase.FAILED -> Text(state.message ?: "Something went wrong.", color = MaterialTheme.colorScheme.error)
                else -> {
                    val rendered = remember(state.answer, state.sources) { state.answer?.let { render(it, state.sources) } }
                    val numbered = rendered?.numbered ?: state.sources
                    if (state.keySentences.isNotEmpty()) KeySentenceBlock(state, numbered, onOpenSource)
                    Progress(state.phase)
                    if (state.modelDeclined && state.phase == AskPhase.DONE) {
                        Notice("The on-device model couldn't write a summary from these passages, so here are the closest sentences from your slides.")
                    } else if (rendered != null && rendered.text.isNotBlank()) {
                        if (state.keySentences.isNotEmpty()) {
                            Text("In short · written on this device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(answerText(rendered, onOpenSource), style = MaterialTheme.typography.bodyLarge)
                        if (state.phase == AskPhase.DONE) CitationNotes(state)
                    }
                    if (state.sources.isNotEmpty()) Sources(numbered, rendered, state.keySentences, state.question, onOpenSource)
                    state.stats?.takeIf { state.phase == AskPhase.DONE }?.let { s ->
                        val load = s.loadMs?.let { " · model loaded in %.1f s".format(it / 1000.0) }.orEmpty()
                        Text(
                            "On this device: first word after %.1f s · %.1f tokens/s%s".format(s.ttftMs / 1000.0, s.decodeTokensPerSecond, load),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The sentences from the passages that best match the question, copied word for word: the part of
 * the answer that can't be made up. Each links to its source with the same number as below.
 */
@Composable
private fun KeySentenceBlock(state: AskUiState, numbered: List<Source>, onOpenSource: (Source) -> Unit) {
    val terms = remember(state.question) { Snippets.queryTerms(state.question) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(keySentenceLabel(state.keySentences), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        state.keySentences.forEach { k ->
            val n = numbered.indexOf(k.source) + 1
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                val snippet = remember(k.text, terms) { Snippets.make(k.text, terms, maxChars = k.text.length) }
                val link = TextLinkStyles(SpanStyle(color = Color.White, background = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 0.75.em, baselineShift = BaselineShift(0.25f)))
                Text(
                    buildAnnotatedString {
                        append("“")
                        append(highlighted(snippet.text, snippet.highlights))
                        append("” ")
                        if (n > 0) withLink(LinkAnnotation.Clickable("source", link) { onOpenSource(k.source) }) { append("\u2009$n\u2009") }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Says where the quoted sentences come from: slides/PDFs, lecture recordings, or both. */
fun keySentenceLabel(keys: List<KeySentence>): String {
    val recordings = keys.count { it.source.hit.chunk.startMs != null }
    return when (recordings) {
        0 -> "From your slides"
        keys.size -> "From your lecture recordings"
        else -> "From your slides and recordings"
    }
}

@Composable
private fun Progress(phase: AskPhase) {
    val label = when (phase) {
        AskPhase.FINDING -> "Finding the passages that cover this…"
        AskPhase.LOADING_MODEL -> "Loading the answering model. The first time takes a little while…"
        AskPhase.WRITING -> "Writing an answer from these passages…"
        else -> null
    } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** The answer with its citation markers as small raised links to the sources. */
@Composable
private fun answerText(r: RenderedAnswer, onOpenSource: (Source) -> Unit) = buildAnnotatedString {
    // Citation numbers as small filled chips, like the landing page's answer card.
    val link = TextLinkStyles(SpanStyle(color = Color.White, background = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 0.75.em, baselineShift = BaselineShift(0.25f)))
    val bad = SpanStyle(color = Color.White, background = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 0.75.em, baselineShift = BaselineShift(0.25f))
    var at = 0
    for (m in r.markers) {
        append(r.text.substring(at, m.range.first))
        // "[1, 2]" shows as " 1, 2 " on the chip: the brackets are for the model, not the reader.
        val label = "\u2009" + r.text.substring(m.range).trim('[', ']') + "\u2009"
        val target = m.sources.firstOrNull()
        if (target == null) {
            pushStyle(bad)
            append(label)
            pop()
        } else {
            withLink(LinkAnnotation.Clickable("source", link) { onOpenSource(target) }) { append(label) }
        }
        at = m.range.last + 1
    }
    append(r.text.substring(at))
}

@Composable
private fun CitationNotes(state: AskUiState) {
    val a = state.answer ?: return
    val notes = buildList {
        if (a.citations.isEmpty()) add("The answer didn't cite a source, so check it against the passages below.")
        if (a.repaired > 0) add("${a.repaired} citation${if (a.repaired == 1) " was" else "s were"} slightly off and matched to the right slide.")
        if (a.unsupported > 0) add("[?] marks ${if (a.unsupported == 1) "a citation" else "citations"} that match none of your passages; treat that part with care.")
    }
    notes.forEach { Notice(it) }
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(R.drawable.ic_help), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun Refused(state: AskUiState, onSearchInstead: (String) -> Unit) {
    Text("Not in your materials", style = MaterialTheme.typography.titleMedium)
    Text(
        (state.message ?: "") + " Retrieve only answers from what you've imported, so it won't guess.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { onSearchInstead(state.question) }) { Text("Search for it instead") }
}

/** The passages given to the model, numbered as in the answer; uncited ones are marked as such. */
@Composable
private fun Sources(numbered: List<Source>, rendered: RenderedAnswer?, keySentences: List<KeySentence>, question: String, onOpenSource: (Source) -> Unit) {
    // Quoted in the key sentences or cited by the model: either way the answer used it.
    val cited = rendered?.markers?.flatMap { it.sources }?.toSet().orEmpty() + keySentences.map { it.source }
    val terms = remember(question) { Snippets.queryTerms(question) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sources", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        numbered.forEachIndexed { i, s ->
            SourceRow(i + 1, s, cited = rendered == null || s in cited, terms = terms, onClick = { onOpenSource(s) })
        }
    }
}

@Composable
private fun SourceRow(n: Int, s: Source, cited: Boolean, terms: Set<String>, onClick: () -> Unit) {
    val c = s.hit.chunk
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).background(if (cited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = MaterialTheme.typography.labelMedium, color = if (cited) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.startMs != null) {
                val color = moduleColor(c.module)
                Box(Modifier.width(88.dp).aspectRatio(4f / 3f).background(color.accent, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Text(timestamp(c.startMs), style = MaterialTheme.typography.titleSmall.copy(fontFamily = Mono), color = Color.White)
                }
            } else {
                SlideImage(c.docId, c.page ?: 1, Modifier.width(88.dp), aspect = 4f / 3f, fallback = locationLabel(s.hit.docType, c.page, null))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModuleBadge(c.module)
                    Text("${Weeks.label(c.week)} › ${locationLabel(s.hit.docType, c.page, c.startMs)}", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    Snippets.make(c.text, terms, maxChars = 140).text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (cited) displayName(s.hit.docName) else "${displayName(s.hit.docName)} · read, not cited",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
