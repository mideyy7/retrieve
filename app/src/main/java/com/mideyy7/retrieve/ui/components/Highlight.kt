package com.mideyy7.retrieve.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.mideyy7.retrieve.ui.theme.RetrieveColors

/** [text] with each of [ranges] marked like a highlighter pen: yellow behind, bold. */
@Composable
fun highlighted(text: String, ranges: List<IntRange>): AnnotatedString {
    val extra = RetrieveColors.extra
    val style = SpanStyle(background = extra.highlight, color = extra.onHighlight, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}
