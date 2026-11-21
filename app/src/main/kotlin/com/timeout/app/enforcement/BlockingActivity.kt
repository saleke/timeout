package com.timeout.app.enforcement

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeout.app.MainActivity
import com.timeout.app.TimeOutApplication
import com.timeout.app.data.AppCatalog
import com.timeout.app.ui.*
import com.timeout.app.ui.theme.TimeOutTheme
import com.timeout.core.DisplayDeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class BlockingActivity : ComponentActivity() {
    private val container get() = (application as TimeOutApplication).container
    private val tick by lazy { MutableStateFlow(container.clock()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val block by container.blockingState.collectAsStateWithLifecycle()
            val appearance by container.appearance.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val app by produceState<com.timeout.app.data.InstalledApp?>(null, block?.packageName) {
                value = block?.packageName?.let { name -> withContext(Dispatchers.IO) { AppCatalog(this@BlockingActivity).find(name) } }
            }
            LaunchedEffect(block?.packageName) { if (block == null) finish() }
            LifecycleResumeEffect(block?.packageName) {
                block?.let { current ->
                    container.standardRuntime.update { it.copy(blockedPackage = current.packageName) }
                }
                val timer = scope.launch { while (isActive) { tick.value = container.clock(); delay(1000) } }
                onPauseOrDispose { timer.cancel(); container.standardRuntime.update { it.copy(blockedPackage = null) } }
            }
            BackHandler { goHome() }
            TimeOutTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Spacer(Modifier.height(48.dp))
                            AppIcon(app, Modifier.size(64.dp))
                            Text(app?.label ?: "This app", style = MaterialTheme.typography.headlineLarge)
                            Text("Blocked for now", style = MaterialTheme.typography.titleLarge)
                            block?.let { current ->
                                current.decision.nextAccessAt?.let { end ->
                                    Text("Access in", style = MaterialTheme.typography.bodyMedium)
                                    AccessCountdown(DisplayDeadline.at(end, current.clock), tick, app?.label ?: "This app")
                                }
                                Text(current.decision.restrictions.joinToString(" + ") { it.rule.name }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        MainButton("Go to Home", ::goHome)
                        TextButton({
                            startActivity(Intent(this@BlockingActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_protection", true))
                            finish()
                        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View plans") }
                    }
                }
            }
        }
    }
    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
