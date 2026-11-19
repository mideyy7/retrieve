package com.mideyy7.retrieve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mideyy7.retrieve.ui.RetrieveApp
import com.mideyy7.retrieve.ui.RetrieveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            // Fresh launch (not a rotation): open the last index for instant search, then rebuild
            // from the database in the background in case the file is stale.
            lifecycleScope.launch { retrieveApp.keywordIndex.openThenRebuild() }
        }
        setContent {
            RetrieveTheme {
                RetrieveApp()
            }
        }
    }
}
