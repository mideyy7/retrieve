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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.mideyy7.retrieve.ui.library.LibraryScreen
import com.mideyy7.retrieve.ui.module.ModuleScreen
import com.mideyy7.retrieve.ui.search.SearchScreen
import com.mideyy7.retrieve.ui.search.SearchViewModel
import kotlinx.serialization.Serializable

@Composable
fun RetrieveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

// Type-safe routes: Navigation Compose serialises these into the back stack, so arguments
// survive process death along with the stack itself.
@Serializable data object SearchTab
@Serializable data object LibraryTab
@Serializable data object SearchRoute
@Serializable data object LibraryRoute
@Serializable data class ModuleRoute(val code: String)

private enum class Tab(val label: String, val icon: ImageVector, val graph: Any) {
    Search("Search", Icons.Filled.Search, SearchTab),
    Library("Library", Icons.AutoMirrored.Filled.List, LibraryTab),
}

/**
 * Bottom bar with one nested graph per tab. Switching tabs saves and restores each tab's back
 * stack (the standard multiple-back-stacks setup), so leaving the Library mid-way through a module
 * and coming back lands on the same page.
 */
@Composable
fun RetrieveApp() {
    val nav = rememberNavController()
    // Scoped to the Activity (this composable sits outside the NavHost), so other screens can hand
    // Search a query through [openSearch] and the query outlives tab switches.
    val searchVm: SearchViewModel = viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    /** Folders are queries: a module or week opens Search with its filter typed in. */
    fun openSearch(query: String) {
        searchVm.onQueryChange(query)
        nav.switchTab(Tab.Search)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val selected = current?.hierarchy?.any { it.hasRoute(t.graph::class) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { if (selected) nav.popToTabRoot(t) else nav.switchTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = SearchTab, modifier = Modifier.padding(padding)) {
            navigation<SearchTab>(startDestination = SearchRoute) {
                composable<SearchRoute> { SearchScreen(vm = searchVm) }
            }
            navigation<LibraryTab>(startDestination = LibraryRoute) {
                composable<LibraryRoute> { LibraryScreen(onOpenModule = { nav.navigate(ModuleRoute(it)) }) }
                composable<ModuleRoute> {
                    ModuleScreen(onBack = { nav.popBackStack() }, onSearch = ::openSearch)
                }
            }
        }
    }
}

private fun NavHostController.switchTab(tab: Tab) = navigate(tab.graph) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Re-tapping the selected tab goes back to its first screen. */
private fun NavHostController.popToTabRoot(tab: Tab) {
    val root: Any = if (tab == Tab.Search) SearchRoute else LibraryRoute
    popBackStack(root, inclusive = false)
}
