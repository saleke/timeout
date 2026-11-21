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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.ui.*
import com.timeout.app.ui.theme.LocalSupportingColors
import com.timeout.core.*
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(state: AppUiState, clock: StateFlow<ClockSnapshot>, onBlock: (LifeContext) -> Unit,
    onRoutine: (LifeContext) -> Unit, onProtection: () -> Unit, onSetup: () -> Unit, onRule: (Rule) -> Unit, onApp: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val policy = state.policy
    val apps = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val rules = remember(policy.sourceRules) { policy.sourceRules.associateBy { it.id } }
    val access = remember(policy, state.runtime.suspendedPackages, state.runtime.releasePending, apps) {
        (policy.decisions.keys + state.runtime.suspendedPackages + state.runtime.releasePending).distinct()
            .filter { policy.decisions[it]?.restricted == true || it in state.runtime.suspendedPackages || it in state.runtime.releasePending }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { apps[it]?.label ?: it })
    }
    val commitments = remember(policy) { policy.sourceRules.filter { it.id in policy.activeRuleIds || it.id in policy.lockedRuleIds } }
    val healthy = state.protectionReady && state.configurationCurrent

    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "date", contentType = "date") {
            Text(state.clock.wallTime.inZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if ((!state.protectionReady && state.rules.any { it.enabled }) || state.error != null) item(key = "health", contentType = "notice") {
            val colors = LocalSupportingColors.current
            Surface(onClick = onSetup, color = colors.warningContainer, contentColor = colors.onWarning, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(R.drawable.ic_warning, tint = colors.onWarning)
                    Column(Modifier.weight(1f)) {
                        Text("App protection is off", fontWeight = FontWeight.Medium)
                        Text("Set up blocking", style = MaterialTheme.typography.bodyMedium)
                    }
                    Symbol(R.drawable.ic_chevron_right, tint = colors.onWarning)
                }
            }
        }
        if (state.protectionReady && !state.configurationCurrent) item(key = "configuration", contentType = "notice") {
            Hint("Updating protection…", R.drawable.ic_schedule)
        }

        item(key = "summary", contentType = "summary") {
            if (access.isEmpty()) Surface(color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Symbol(R.drawable.ic_brand_mark, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.rules.isEmpty()) "Make time\nfor what matters." else "You're clear for now.", style = MaterialTheme.typography.headlineLarge)
                    MainButton("Block now", { onBlock(LifeContext.STUDY) }, icon = R.drawable.ic_lock)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("App access", style = MaterialTheme.typography.headlineLarge)
                Text("${appCount(access.size)} · ${commitments.size} ${if (commitments.size == 1) "plan" else "plans"}",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                MainButton("Block now", { onBlock(LifeContext.STUDY) }, icon = R.drawable.ic_add)
            }
        }

        items(access, key = { "access:$it" }, contentType = { "app-access" }) { name ->
            AppAccessCard(name, apps[name], policy.decisions[name] ?: AccessDecision(emptyList(), null),
                policy.applicableRuleIds[name].orEmpty().mapNotNull(rules::get), policy.clock, clock,
                name in state.runtime.suspendedPackages, name in state.runtime.releasePending,
                mode = state.runtime.mode, ready = healthy, onClick = { onApp(name) })
        }

        if (commitments.isNotEmpty()) item(key = "commitments-title", contentType = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Sessions and routines")
            }
        }
        items(commitments, key = { "commitment:${it.id}" }, contentType = { "commitment" }) { rule ->
            Surface(onClick = { onRule(rule) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Symbol(rule.context.icon(), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                        val labels = rule.packages.map { apps[it]?.label ?: it }
                        Text(if (labels.size <= 3) labels.joinToString(", ") else "${labels.take(3).joinToString(", ")} + ${labels.size - 3} more",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val end = CommitmentPolicy.endsAt(rule, policy.clock)
                        Text(if (rule.kind == RuleKind.QUICK_BLOCK) "Ends ${shortTimeLabel(context, end, policy.clock.wallTime)}"
                            else if (rule.id in policy.lockedRuleIds) "Editable ${shortTimeLabel(context, end, policy.clock.wallTime)}"
                            else ruleSummary(context, rule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Symbol(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (policy.upcoming.isNotEmpty()) item(key = "upcoming-title", contentType = "heading") { SectionTitle("Coming up") }
        items(policy.upcoming, key = { "event:${it.rule.id}:${it.at}" }, contentType = { "event" }) { event ->
            Surface(onClick = { onRule(event.rule) }, color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Symbol(if (event.restrictionBegins) R.drawable.ic_lock else R.drawable.ic_check, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${timeLabel(context, event.at)} · ${dateLabel(context, event.at)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (event.rule.kind == RuleKind.QUICK_BLOCK) "${event.rule.name} ends"
                            else "${event.rule.name}: ${if (event.restrictionBegins) "restriction begins" else "this rule permits access"}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (access.isNotEmpty()) item(key = "view-protection", contentType = "action") {
            OutlinedButton(onProtection, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { Text("View all protection") }
        }
        item(key = "presets-title", contentType = "heading") { SectionTitle(if (state.rules.isEmpty()) "Make room for your day" else "Build a routine") }
        item(key = "study", contentType = "preset") { ActionRow("Study", "A focused session, when you need it", R.drawable.ic_school, { onBlock(LifeContext.STUDY) }) }
        item(key = "work", contentType = "preset") { ActionRow("Work", "Protect hours or plan your check-ins", R.drawable.ic_work, { onRoutine(LifeContext.WORK) }) }
        item(key = "bedtime", contentType = "preset") { ActionRow("Bedtime", "Let your evening wind down", R.drawable.ic_bedtime, { onRoutine(LifeContext.BEDTIME) }) }
        item(key = "personal", contentType = "preset") { ActionRow("Personal", "Time for something of your own", R.drawable.ic_shield, { onRoutine(LifeContext.PERSONAL) }) }
    }
}
