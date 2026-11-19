package com.mideyy7.retrieve.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mideyy7.retrieve.R
import com.mideyy7.retrieve.data.DocType
import com.mideyy7.retrieve.data.Document
import com.mideyy7.retrieve.data.Weeks
import com.mideyy7.retrieve.search.Snippets
import com.mideyy7.retrieve.ui.components.ModuleBadge
import com.mideyy7.retrieve.ui.components.citation
import com.mideyy7.retrieve.ui.components.copyText
import com.mideyy7.retrieve.ui.components.displayName
import com.mideyy7.retrieve.ui.components.highlighted
import com.mideyy7.retrieve.ui.components.openOriginal
import com.mideyy7.retrieve.ui.components.shareText
import com.mideyy7.retrieve.ui.components.timestamp
import com.mideyy7.retrieve.ui.slides.SlideImage
import com.mideyy7.retrieve.ui.theme.Mono
import com.mideyy7.retrieve.ui.theme.moduleColor
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * The place a result points to, in context: the slide itself (swipe for the neighbouring slides)
 * with its text below and the search words highlighted, or a recording's transcript scrolled to the
 * moment. From here the student can copy where it is, share it, or open the original file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(onBack: () -> Unit, vm: ViewerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(missing) { if (missing) onBack() }

    val s = state
    val pager = rememberPagerState(initialPage = s?.initialIndex ?: 0) { s?.pages?.size ?: 0 }
    // The state arrives after the first frame; jump to the target page once it does.
    LaunchedEffect(s?.initialIndex) { s?.let { pager.scrollToPage(it.initialIndex) } }
    val current = s?.pages?.getOrNull(pager.currentPage)

    fun where(page: ViewerPage) = citation(s!!.doc.module, s.doc.week, s.doc.name, s.doc.type, page.page, page.startMs)
    fun say(text: String) = scope.launch { snackbar.showSnackbar(text) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    s?.doc?.let { d ->
                        Column {
                            Text(displayName(d.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("${d.module} · ${Weeks.label(d.week)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (s != null && current != null) {
                        IconButton(onClick = { if (copyText(context, "Citation", where(current))) say("Citation copied") }) {
                            Icon(painterResource(R.drawable.ic_copy), "Copy citation")
                        }
                        IconButton(onClick = { shareText(context, "“${current.text.take(600)}”\n— ${where(current)}") }) {
                            Icon(Icons.Filled.Share, "Share passage")
                        }
                        if (s.doc.type != DocType.AUDIO) {
                            IconButton(onClick = {
                                if (!openOriginal(context, s.doc.sourceUri, "application/pdf")) say("No app here can open PDFs")
                            }) { Icon(painterResource(R.drawable.ic_open_in_new), "Open the PDF in another app") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (s == null) return@Scaffold
        val terms = remember(vm.route.query) { Snippets.queryTerms(vm.route.query) }
        if (s.doc.type == DocType.AUDIO) {
            Transcript(s, terms, Modifier.padding(padding))
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                HorizontalPager(pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1, key = { s.pages[it].page ?: it }) { i ->
                    SlidePage(s.doc, s.pages[i], terms)
                }
                PagerBar(
                    label = "${if (s.doc.type == DocType.SLIDES) "Slide" else "Page"} ${current?.page ?: "–"} of ${s.pages.size}",
                    canBack = pager.currentPage > 0,
                    canForward = pager.currentPage < s.pages.lastIndex,
                    onBack = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                    onForward = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                )
            }
        }
    }
}

@Composable
private fun SlidePage(doc: Document, page: ViewerPage, terms: Set<String>) {
    val n = page.page ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SlideImage(
            doc.id, n,
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            contentDescription = "${if (doc.type == DocType.SLIDES) "Slide" else "Page"} $n of ${displayName(doc.name)}",
            fallback = "Can't open the file to show this page",
        )
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Text on this ${if (doc.type == DocType.SLIDES) "slide" else "page"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page.text.isBlank()) {
                Text("No text found here (a picture or a blank page).", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val full = remember(page.text, terms) { Snippets.make(page.text, terms, maxChars = page.text.length) }
                Text(highlighted(full.text, full.highlights), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PagerBar(label: String, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(onClick = onBack, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
            Text(label, style = MaterialTheme.typography.labelLarge)
            FilledTonalIconButton(onClick = onForward, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        }
    }
}

/** The whole transcript, opened at the moment the result points to, which is marked. */
@Composable
private fun Transcript(s: ViewerState, terms: Set<String>, modifier: Modifier) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = s.initialIndex)
    val accent = moduleColor(s.doc.module)
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                ModuleBadge(s.doc.module)
                Icon(painterResource(R.drawable.ic_captions), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Lecture recording transcript", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(s.pages, key = { _, p -> p.startMs ?: 0 }) { i, p ->
            val target = i == s.initialIndex
            val text = remember(p.text, terms) { Snippets.make(p.text, terms, maxChars = p.text.length) }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (target) accent.container else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.background(if (target) accent.accent else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        timestamp(p.startMs ?: 0),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                        color = if (target) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(highlighted(text.text, text.highlights), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}
