package com.mideyy7.retrieve.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mideyy7.retrieve.ui.theme.moduleColor

/** The module code on its own colour, so a module is recognisable at a glance on every screen. */
@Composable
fun ModuleBadge(module: String, modifier: Modifier = Modifier) {
    val c = moduleColor(module)
    Text(
        module,
        style = MaterialTheme.typography.labelMedium,
        color = androidx.compose.ui.graphics.Color.White,
        modifier = modifier
            .background(c.accent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
