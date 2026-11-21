package com.timeout.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeout.app.BuildConfig
import com.timeout.app.ProtectionMode
import com.timeout.app.R
import com.timeout.app.data.DraftScreen
import com.timeout.app.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: TimeOutViewModel, state: AppUiState, fromDraft: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val draft by vm.draft.collectAsStateWithLifecycle()
    val active by rememberUpdatedState(LocalDestinationActive.current)
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }

    fun openSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { vm.cancelSettingsHandoff(); vm.setupMessage("Open Android Settings → Accessibility → Timeout app protection.") }
    }
    fun appInfo() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
            .onFailure { vm.cancelSettingsHandoff(); vm.setupMessage("Open Timeout's app info in Android Settings.") }
    }

    DetailScaffold("App protection", onBack, bottom = {
        BottomAction {
            if (state.protectionReady) MainButton(if (fromDraft) "Continue" else "Done", onBack, icon = R.drawable.ic_check)
            else MainButton(if (state.accessibilityEnabled) "Open Android settings" else "Enable app blocking", {
                if (state.disclosureAccepted) vm.openSettings(fromDraft, DraftScreen.BUILDER) { if (active) openSettings() } else disclosure = true
            }, busy = state.busy, icon = R.drawable.ic_shield)
            if (fromDraft && draft.kind != com.timeout.core.RuleKind.QUICK_BLOCK && !state.protectionReady)
                TextButton({ vm.saveDraft(asDraft = true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save routine for later") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "intro") {
                Symbol(R.drawable.ic_shield, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text(if (state.protectionReady) "Ready to block." else "Turn on app blocking.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                Text(if (state.protectionReady) "Return to your plan to start."
                    else "Enable Timeout app protection in Android's accessibility settings.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusLine("Android access", if (state.accessibilityEnabled || state.runtime.mode == ProtectionMode.MANAGED) "Enabled" else "Not enabled")
                    StatusLine("App protection", if (state.protectionReady) "Ready" else if (state.accessibilityEnabled) "Not connected" else "Off")
                    if (state.accessibilityEnabled && !state.runtime.connected)
                        Text("Turn the service off and on again in Android settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.runtime.connected) state.runtime.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item(key = "help") {
                TextButton({ help = true }, Modifier.heightIn(min = 48.dp)) { Text("Android won't let me enable it") }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
        title = { Text("Allow app protection?") },
        text = { Text("Timeout uses Android's AccessibilityService API to detect selected apps opening and show your blocking screen. It does not read screen text or messages. Your plans stay on this device.") },
        confirmButton = { TextButton({ disclosure = false; vm.acceptStandardProtection(fromDraft) { if (active) openSettings() } }) { Text("Continue to Android") } },
        dismissButton = { TextButton({ disclosure = false }) { Text("Not now") } })
    if (help) ModalBottomSheet(onDismissRequest = { help = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Restricted settings", style = MaterialTheme.typography.titleLarge)
            Text("For a downloaded APK, Android may require App info → menu → Allow restricted settings. Then return to Accessibility and turn on Timeout.")
            MainButton("Open app info", { help = false; vm.openSettings(fromDraft, DraftScreen.SETUP) { if (active) appInfo() } })
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SettingsScreen(vm: TimeOutViewModel, state: AppUiState, onBack: () -> Unit, onSetup: () -> Unit, onAbout: () -> Unit) {
    val appearance by vm.container.appearance.collectAsStateWithLifecycle()
    val reducedMotion by vm.container.reducedMotion.collectAsStateWithLifecycle()
    var delete by rememberSaveable { mutableStateOf(false) }
    var removeManagement by rememberSaveable { mutableStateOf(false) }
    val locked = state.lockedRules
    DetailScaffold("Settings", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "protection") { ActionRow("App protection", if (state.protectionReady) "On" else "Set up blocking", R.drawable.ic_shield, onSetup) }
            item(key = "appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Appearance")
                    AdaptiveChoices(listOf(ChoiceOption("system", "System"), ChoiceOption("light", "Light"), ChoiceOption("dark", "Dark")),
                        setOf(appearance), vm.container::setAppearance)
                    Row(Modifier.fillMaxWidth().toggleable(reducedMotion, role = Role.Switch, onValueChange = vm.container::setReducedMotion).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Reduce page motion", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(reducedMotion, null, Modifier.clearAndSetSemantics {})
                    }
                }
            }
            item(key = "data") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Data")
                    if (locked.isNotEmpty()) Text("Deletion is available after your commitments end.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ delete = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = locked.isEmpty() && state.error == null && !state.busy) {
                        Symbol(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Delete plans and history")
                    }
                    if (state.deviceOwner && BuildConfig.ALLOW_TEST_DEVICE_REMOVAL)
                        OutlinedButton({ removeManagement = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = locked.isEmpty() && !state.busy) { Text("Remove test-device management") }
                }
            }
            item(key = "about") { ActionRow("How protection works", "Capabilities and privacy", R.drawable.ic_info, onAbout) }
            item(key = "version") { Text("Timeout ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete plans and history?") },
        text = { Text("Unlocked routines will stop and local records will be deleted.") },
        confirmButton = { TextButton({ delete = false; vm.deleteData() }, enabled = locked.isEmpty()) { Text("Delete") } },
        dismissButton = { TextButton({ delete = false }) { Text("Cancel") } })
    if (removeManagement) AlertDialog(onDismissRequest = { removeManagement = false }, title = { Text("Remove test management?") },
        text = { Text("Unlocked routines will stop. Timeout will release its suspended apps before removing test-device ownership.") },
        confirmButton = { TextButton({ removeManagement = false; vm.removeManagement() }, enabled = locked.isEmpty()) { Text("Remove") } },
        dismissButton = { TextButton({ removeManagement = false }) { Text("Cancel") } })
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    DetailScaffold("How protection works", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { SectionTitle("App-opening blocks"); Spacer(Modifier.height(8.dp)); Text("With accessibility enabled, Timeout shows a blocking screen when a selected app opens. It does not change system volume or pause unrelated audio. This is not OS-level suspension; background audio, other profiles, and alternate apps are not guaranteed to be blocked.") }
            item { SectionTitle("Your commitment"); Spacer(Modifier.height(8.dp)); Text("Sessions have no early-stop action in Timeout. Routine settings unlock on your chosen date. Android permissions and uninstallation remain system controls.") }
            item { SectionTitle("Overlapping plans"); Spacer(Modifier.height(8.dp)); Text("Every applicable plan must allow access. Each app's clock includes all its sessions and routines. One session finishing may not release an app.") }
            item { SectionTitle("Essential access"); Spacer(Modifier.height(8.dp)); Text("Calls, system controls, alarms, keyboards, accessibility services, and Timeout are excluded. Keep any authentication, medical, navigation, or communication apps you need available.") }
            item { SectionTitle("Privacy"); Spacer(Modifier.height(8.dp)); Text("The accessibility service does not retrieve screen content. Plans and history stay here, with no network permission. History keeps up to 30 days of local actions.") }
            item { SectionTitle("Existing managed installations"); Spacer(Modifier.height(8.dp)); Text("Already provisioned device-owner installations can retain their package-suspension backend and finite native release. Managed provisioning is not needed for normal app-opening blocks.") }
        }
    }
}
