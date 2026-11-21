package com.timeout.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.ui.*
import com.timeout.core.*

@Composable
fun PlansScreen(state: AppUiState, onCreate: (LifeContext) -> Unit, onRule: (Rule) -> Unit) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = filter?.let { LifeContext.valueOf(it) }
    val routines = remember(state.rules, selected) { state.rules.filter { it.kind != RuleKind.QUICK_BLOCK && (selected == null || it.context == selected) } }
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "intro", contentType = "intro") {
            Text("Good boundaries.\nLess daily deciding.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("Choose your hours and the date your settings unlock. Let the routine hold.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "create", contentType = "action") { MainButton("Set a routine", { onCreate(selected ?: LifeContext.PERSONAL) }, icon = R.drawable.ic_add) }
        item(key = "filter", contentType = "filter") { ContextChoices(selected, { filter = it.name }, true, { filter = null }) }
        if (routines.isEmpty()) item(key = "empty", contentType = "empty") {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(R.drawable.ic_calendar_today, tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.rules.none { it.kind != RuleKind.QUICK_BLOCK }) "Your first routine starts here" else "No routines in this view", style = MaterialTheme.typography.titleLarge)
                    Text(if (selected == null) "Choose apps, decide when they are available, and review what happens next." else "This filter only changes the view. Other routines keep their settings.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(routines, key = { it.id }, contentType = { "routine" }) { rule -> RuleCard(rule, state, { onRule(rule) }) }
        item(key = "scope", contentType = "notice") { Hint("Study, Work, Bedtime, and Personal can run together. Every applicable rule still counts.") }
    }
}

@Composable
fun RuleCard(rule: Rule, state: AppUiState, onClick: () -> Unit) {
    val context = LocalContext.current
    val restricted = rule.id in state.policy.activeRuleIds
    Surface(onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(rule.context.icon(), tint = MaterialTheme.colorScheme.primary)
                Text(rule.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Symbol(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(ruleSummary(context, rule), style = MaterialTheme.typography.bodyLarge)
            if (rule.id in state.policy.lockedRuleIds) {
                val end = CommitmentPolicy.endsAt(rule, state.clock)
                Text("Locked until ${dateLabel(context, end)}, ${timeLabel(context, end)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppStack(rule.packages, state.apps, Modifier.weight(1f))
                Text(if (!rule.enabled) "Off" else if (restricted) "Restriction applies" else "Scheduled",
                    style = MaterialTheme.typography.labelLarge, color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun InsightsScreen(state: AppUiState, onBlock: () -> Unit) {
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "intro", contentType = "intro") {
            Text("Small boundaries.\nA clearer picture.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text("A private history of the choices you make here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.history.isEmpty()) item(key = "empty", contentType = "empty") {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Symbol(R.drawable.ic_bar_chart, tint = MaterialTheme.colorScheme.primary)
                    Text("Your history starts with you", style = MaterialTheme.typography.titleLarge)
                    Text("Sessions and routine changes will appear here. There are no scores to chase and no streak to lose.", style = MaterialTheme.typography.bodyLarge)
                    MainButton("Start a session", onBlock)
                }
            }
        }
        items(state.history, key = { it.id }, contentType = { "history" }) { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Symbol(R.drawable.ic_schedule, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                    Text(event.detail, style = MaterialTheme.typography.bodyMedium)
                    Text("${dateLabel(LocalContext.current, event.at)} · ${timeLabel(LocalContext.current, event.at)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "scope", contentType = "notice") { Hint("History records saved actions and session ends. It does not measure productive time or claim time saved.") }
    }
}
