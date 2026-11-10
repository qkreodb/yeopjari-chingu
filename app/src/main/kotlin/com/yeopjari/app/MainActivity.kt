package com.yeopjari.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yeopjari.app.ui.MainScreen
import com.yeopjari.app.ui.YeopjariTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as YeopjariApp).controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            YeopjariTheme {
                MainScreen(state = state, controller = controller)
            }
        }
    }
}
