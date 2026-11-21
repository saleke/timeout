package com.timeout.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeout.app.R
import com.timeout.app.data.PendingChange
import com.timeout.app.ui.*
import com.timeout.core.*
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ProtectionScreen(state: AppUiState, onBack: () -> Unit, onRule: (Rule) -> Unit, onSetup: () -> Unit,
    packageName: String?, clock: StateFlow<ClockSnapshot>, onApp: (String) -> Unit) {
    val policy = state.policy
    val apps = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val rules = remember(policy.sourceRules) { policy.sourceRules.associateBy { it.id } }
    val packages = remember(policy, packageName, state.runtime.releasePending) {
        if (packageName != null) listOf(packageName) else (policy.decisions.keys + state.runtime.releasePending).distinct()
    }
    val scroll = rememberLazyListState()
    DetailScaffold(packageName?.let { apps[it]?.label } ?: "App access", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = scroll, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!state.protectionReady) item(key = "setup") { ActionRow("App protection is off", "Set up blocking", R.drawable.ic_warning, onSetup) }
            items(packages, key = { "app:$it" }, contentType = { "app" }) { name ->
                AppAccessCard(name, apps[name], policy.decisions[name] ?: AccessDecision(emptyList(), null),
                    policy.applicableRuleIds[name].orEmpty().mapNotNull(rules::get), policy.clock, clock,
                    name in state.runtime.suspendedPackages, name in state.runtime.releasePending,
                    mode = state.runtime.mode, ready = state.protectionReady,
                    onClick = if (packageName == null) ({ onApp(name) }) else null)
            }
            if (packageName != null) {
                val applicable = policy.applicableRuleIds[packageName].orEmpty().mapNotNull(rules::get)
                if (applicable.isNotEmpty()) item(key = "plans-title") { SectionTitle("Plans for this app") }
                items(applicable, key = { "rule:${it.id}" }, contentType = { "rule" }) { rule ->
                    ActionRow(rule.name, ruleSummary(LocalContext.current, rule), rule.context.icon(), { onRule(rule) })
                }
            }
            if (packages.isEmpty()) item(key = "empty") { Text("No active app plans.", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
fun RuleDetailScreen(vm: TimeOutViewModel, state: AppUiState, ruleId: String, onBack: () -> Unit, onEdit: (Rule) -> Unit, onRepeat: (Rule) -> Unit, onSetup: () -> Unit) {
    val rule = state.rules.firstOrNull { it.id == ruleId }
    val context = LocalContext.current
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val locked = rule?.let { CommitmentPolicy.isLocked(it, state.clock) } == true
    val scroll = rememberLazyListState()
    val apps = remember(state.apps) { state.apps.associateBy { it.packageName } }
    DetailScaffold(rule?.name ?: "Plan", onBack) { padding ->
        if (rule == null) Text("This plan is no longer here.", Modifier.padding(padding).padding(24.dp))
        else LazyColumn(Modifier.fillMaxSize().padding(padding), state = scroll, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "summary") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(rule.name, style = MaterialTheme.typography.headlineLarge)
                    Text(if (rule.kind == RuleKind.QUICK_BLOCK) "Ends ${shortTimeLabel(context, RuleEngine.effectiveEnd(rule, state.clock), state.clock.wallTime)}"
                        else ruleSummary(context, rule), style = MaterialTheme.typography.bodyLarge)
                    Text(if (!rule.enabled) "Off" else if (rule.id in state.policy.activeRuleIds) "Applies now" else "Scheduled",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (locked) item(key = "lock") {
                val end = CommitmentPolicy.endsAt(rule, state.clock)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (rule.kind == RuleKind.QUICK_BLOCK) "Session locked" else "Settings locked", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Until ${shortTimeLabel(context, end, state.clock.wallTime)}", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item(key = "apps-title") { SectionTitle("Apps") }
            items(rule.packages, key = { "app:$it" }, contentType = { "app" }) { name ->
                val decision = state.policy.decisions[name]
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(apps[name])
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(apps[name]?.label ?: name, style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            state.policy.sourceRules != state.rules -> "Updating…"
                            decision?.restricted == true && decision.restrictions.size > 1 -> "${decision.restrictions.size} rules apply"
                            decision?.restricted == true -> "Blocked by this plan"
                            else -> "Available now"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val change = state.changes.firstOrNull { it.ruleId == rule.id }
            if (change != null && rule.commitment != Commitment.LOCKED) item(key = "legacy-change") { LegacyChangeCard(vm, state, change, rule) }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!locked && rule.kind == RuleKind.QUICK_BLOCK) {
                        if (rule.enabled && rule.id in state.policy.activeRuleIds) MainButton("End session", { confirmStop = true }, busy = state.busy)
                        else MainButton("Start another session", { onRepeat(rule) })
                    } else if (!locked) {
                        MainButton(if (rule.enabled) "Turn routine off" else "Review and turn on", {
                            if (rule.enabled) confirmStop = true else onEdit(rule.copy(enabled = true))
                        }, busy = state.busy)
                        OutlinedButton({ onEdit(rule) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit routine") }
                    }
                    if (!state.protectionReady) TextButton(onSetup, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Set up app protection") }
                }
            }
        }
    }
    if (confirmStop && rule != null && !locked) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("Stop this plan?") },
        text = { Text("Other sessions and routines stay active.") },
        confirmButton = { TextButton({ confirmStop = false; vm.setEnabled(rule, false) }) { Text(if (rule.commitment == Commitment.FIRM) "Request change" else "Stop") } },
        dismissButton = { TextButton({ confirmStop = false }) { Text("Cancel") } })
}

@Composable
private fun LegacyChangeCard(vm: TimeOutViewModel, state: AppUiState, change: PendingChange, rule: Rule) {
    val clock by vm.clock.collectAsStateWithLifecycle()
    val wait = vm.container.repository.waitRemaining(change, clock).coerceAtLeast(0)
    val expired = change.expiresAt <= clock.wallTime
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (expired) "Change request expired" else if (wait > 0) "Confirm in ${(wait + 999) / 1000}s" else "Change ready", style = MaterialTheme.typography.titleMedium)
            if (!expired) MainButton("Confirm change", { vm.confirmChange(rule.id) }, enabled = wait == 0L, busy = state.busy)
            TextButton({ vm.cancelChange(rule.id) }) { Text("Cancel request") }
        }
    }
}
