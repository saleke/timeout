package com.timeout.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeout.app.ui.TimeOutApp
import com.timeout.app.ui.TimeOutViewModel
import com.timeout.app.ui.theme.TimeOutTheme

class MainActivity : ComponentActivity() {
    private var protectionSignal by mutableLongStateOf(0)
    private var draftSignal by mutableLongStateOf(0)
    private val container get() = (application as TimeOutApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("open_protection", false)) protectionSignal = System.nanoTime()
        if (intent.getBooleanExtra("open_draft", false)) draftSignal = System.nanoTime()
        setContent {
            val appearance by container.appearance.collectAsStateWithLifecycle()
            val dark = when (appearance) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val vm: TimeOutViewModel = viewModel()
            TimeOutTheme(appearance) { TimeOutApp(vm, protectionSignal, draftSignal) }
        }
    }

    override fun onStart() {
        super.onStart()
        container.refreshSignals.tryEmit(Unit)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_protection", false)) protectionSignal = System.nanoTime()
        if (intent.getBooleanExtra("open_draft", false)) draftSignal = System.nanoTime()
    }
}
