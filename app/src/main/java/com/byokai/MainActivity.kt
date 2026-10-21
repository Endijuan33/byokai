package com.byokai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.byokai.ui.ByokaiApp
import com.byokai.ui.theme.ByokaiTheme

/**
 * Single-activity host for the BYOKAI Compose UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ByokaiTheme {
                ByokaiApp()
            }
        }
    }
}
