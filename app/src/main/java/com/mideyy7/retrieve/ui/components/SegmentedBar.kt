package com.mideyy7.retrieve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A thin bar split into equal segments, one colour each. Decorative: callers describe it in words. */
@Composable
fun SegmentedBar(colors: List<Color>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { color ->
            Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
        }
    }
}
