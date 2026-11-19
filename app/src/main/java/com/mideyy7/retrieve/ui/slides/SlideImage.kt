package com.mideyy7.retrieve.ui.slides

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.retrieveApp

private sealed interface Slide {
    data object Loading : Slide
    data object Missing : Slide
    data class Ready(val bitmap: Bitmap) : Slide
}

/**
 * A rendered page of an imported PDF. With [aspect] set the frame keeps that shape and the page is
 * fitted inside it (thumbnails line up); without it the frame takes the page's own shape once drawn.
 * If the file can't be opened the frame shows [fallback] (or the page number) instead.
 */
@Composable
fun SlideImage(
    docId: Long,
    page: Int,
    modifier: Modifier = Modifier,
    aspect: Float? = null,
    contentDescription: String? = null,
    fallback: String = "Page $page",
) {
    val renderer = LocalContext.current.retrieveApp.slides
    val shape = MaterialTheme.shapes.small
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val slide by produceState<Slide>(Slide.Loading, docId, page, widthPx) {
            value = renderer.render(docId, page, widthPx)?.let { Slide.Ready(it) } ?: Slide.Missing
        }
        val ratio = aspect ?: (slide as? Slide.Ready)?.bitmap?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(slide, label = "slide") { s ->
                when (s) {
                    is Slide.Ready -> Image(
                        s.bitmap.asImageBitmap(),
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White),
                    )
                    Slide.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(fallback, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slide.Loading -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}
