package com.mideyy7.retrieve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mideyy7.retrieve.ui.RetrieveApp
import com.mideyy7.retrieve.ui.RetrieveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetrieveTheme {
                RetrieveApp()
            }
        }
    }
}
