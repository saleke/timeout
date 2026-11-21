package com.timeout.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeout.app.R
import com.timeout.app.ProtectionMode
import com.timeout.app.data.InstalledApp
import com.timeout.core.*
import kotlinx.coroutines.flow.StateFlow

/** Only this small text node observes seconds. Its parent list never observes the ticking clock. */
@Composable
fun AccessCountdown(deadline: DisplayDeadline, clock: StateFlow<ClockSnapshot>, appName: String, modifier: Modifier = Modifier) {
    val tick = clock.collectAsStateWithLifecycle()
    val text by remember(deadline, tick) { derivedStateOf {
        val remaining = deadline.remaining(tick.value)
        if (remaining <= 0) "Updating…" else {
            val total = remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
            val seconds = (total % 60).toString().padStart(2, '0')
            val minutes = (total / 60 % 60).toString().padStart(2, '0')
            when {
                total >= 86_400 -> "${total / 86_400}d ${total / 3600 % 24}h"
                total >= 3600 -> "${total / 3600}:$minutes:$seconds"
                else -> "${(total / 60).toString().padStart(2, '0')}:$seconds"
            }
        }
    } }
    val spoken by remember(deadline, tick, appName) { derivedStateOf {
        val remaining = deadline.remaining(tick.value)
        if (remaining <= 0) "$appName. Updating access."
        else {
            val seconds = remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
            val parts = listOf(seconds / 86_400 to "day", seconds / 3600 % 24 to "hour", seconds / 60 % 60 to "minute", seconds % 60 to "second")
                .filter { it.first > 0 }.joinToString(", ") { (value, unit) -> "$value $unit${if (value == 1L) "" else "s"}" }
            "$appName. Access in $parts."
        }
    } }
    Text(text, modifier.clearAndSetSemantics { contentDescription = spoken },
        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.primary)
}

@Composable
fun AppAccessCard(packageName: String, app: InstalledApp?, decision: AccessDecision, applicable: List<Rule>,
    evaluatedAt: ClockSnapshot, clock: StateFlow<ClockSnapshot>, nativeSuspended: Boolean, releasePending: Boolean,
    mode: ProtectionMode = ProtectionMode.STANDARD, ready: Boolean = true, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val name = app?.label ?: packageName
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (onClick != null) Symbol(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (decision.restricted) {
                val end = decision.nextAccessAt
                if (end != null) {
                    Text(if (ready) "Access in" else "Planned access", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AccessCountdown(remember(end, evaluatedAt) { DisplayDeadline.at(end, evaluatedAt) }, clock, name)
                        Text(shortTimeLabel(context, end, evaluatedAt.wallTime), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    }
                } else Text("Access depends on overlapping plans", style = MaterialTheme.typography.titleMedium)
            } else Text(if (nativeSuspended || releasePending) "Releasing…" else "Available now", style = MaterialTheme.typography.bodyMedium)

            val names = applicable.map { it.name }.distinct()
            if (names.isNotEmpty()) Text(if (names.size <= 2) names.joinToString(" + ") else "${names.take(2).joinToString(" + ")} + ${names.size - 2} more",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (releasePending) Text("Release needs attention", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
    }
    if (onClick == null) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, content = content)
    else Surface(onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, content = content)
}
