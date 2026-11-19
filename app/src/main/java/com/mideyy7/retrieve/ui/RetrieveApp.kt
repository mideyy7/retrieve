package com.mideyy7.retrieve.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.mideyy7.retrieve.ui.library.LibraryScreen

@Composable
fun RetrieveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** Single screen for now; the Search screen and navigation arrive with the index (Phase 2). */
@Composable
fun RetrieveApp() {
    LibraryScreen()
}
