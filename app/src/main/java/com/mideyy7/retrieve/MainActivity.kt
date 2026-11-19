package com.mideyy7.retrieve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mideyy7.retrieve.ui.RetrieveApp
import com.mideyy7.retrieve.ui.theme.RetrieveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only, so the status and navigation bars always use dark icons, even when
        // the phone is in dark mode (the default would pick light icons and they'd vanish).
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (savedInstanceState == null) {
            // Fresh launch (not a rotation): open the last index for instant search, then rebuild
            // from the database in the background in case the file is stale.
            lifecycleScope.launch { retrieveApp.keywordIndex.openThenRebuild() }
            lifecycleScope.launch { retrieveApp.prepareSemanticSearch() }
        }
        setContent {
            RetrieveTheme {
                RetrieveApp()
            }
        }
    }
}
