package com.mideyy7.retrieve.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mideyy7.retrieve.ui.library.LibraryScreen
import com.mideyy7.retrieve.ui.search.SearchScreen

@Composable
fun RetrieveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Filled.Search),
    Library("Library", Icons.AutoMirrored.Filled.List),
}

/** Two tabs; a navigation library isn't worth it for this (spec §10). */
@Composable
fun RetrieveApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Search) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Search -> SearchScreen(Modifier.padding(padding))
            Tab.Library -> LibraryScreen(Modifier.padding(padding))
        }
    }
}
