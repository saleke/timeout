package com.timeout.app.enforcement

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timeout.app.TimeOutApplication
import com.timeout.app.data.commitOrThrow
import com.timeout.app.ui.MainButton
import com.timeout.app.ui.theme.TimeOutTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only Android's provisioning controller can launch these BIND_DEVICE_ADMIN-protected actions. */
class ProvisioningModeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = if (Build.VERSION.SDK_INT >= 31)
            intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES).orEmpty()
        else emptyList()
        if (Build.VERSION.SDK_INT >= 31 && DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE in allowed) {
            setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        } else setResult(RESULT_CANCELED)
        finish()
    }
}

class PolicyComplianceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TimeOutApplication).container
        setContent {
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var error by rememberSaveable { mutableStateOf<String?>(null) }
            TimeOutTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("A device for\nstronger boundaries.", style = MaterialTheme.typography.headlineLarge)
                            Text("Time Out is being provisioned as this device's owner. It uses Android package suspension for the apps and times you explicitly confirm.")
                            Text("Sessions cannot be stopped before expiry. Routine settings stay locked until your chosen commitment date. During a locked commitment, there is no early-stop, recovery, or data-deletion override.")
                            Text("Essential system access stays available. This app does not use accessibility to read your screen and has no network permission. Your rules stay here.")
                            Text("This development build offers test-device management removal in Settings after all commitments end. Android's production deprovisioning normally involves a device reset. Nothing starts automatically when provisioning finishes.")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        MainButton("Accept and finish setup", {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        container.preferences.commitOrThrow("Your setup choice could not be saved. Free storage and try again.") {
                                            putBoolean("managed_disclosure", true)
                                        }
                                    }
                                    container.refreshSignals.tryEmit(Unit)
                                    setResult(RESULT_OK)
                                    finish()
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    error = failure.message ?: "Your setup choice could not be saved. Try again."
                                } finally { saving = false }
                            }
                        }, busy = saving)
                        TextButton({ setResult(RESULT_CANCELED); finish() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Go back") }
                    }
                }
            }
        }
    }
}
