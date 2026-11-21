package com.timeout.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeout.app.R
import com.timeout.app.data.InstalledApp
import com.timeout.core.*
import com.timeout.app.ui.theme.DesignTokens
import com.timeout.app.ui.theme.LocalReducedMotion
import com.timeout.app.ui.theme.TimeOutEaseOut
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalDestinationActive = staticCompositionLocalOf { true }
val LocalPageSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
fun Symbol(@DrawableRes icon: Int, modifier: Modifier = Modifier, description: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(icon), description, modifier.size(24.dp), tint = tint)
}

fun LifeContext.icon(): Int = when (this) {
    LifeContext.STUDY -> R.drawable.ic_school
    LifeContext.WORK -> R.drawable.ic_work
    LifeContext.BEDTIME -> R.drawable.ic_bedtime
    LifeContext.PERSONAL -> R.drawable.ic_shield
}

@Composable
fun AppIcon(app: InstalledApp?, modifier: Modifier = Modifier) {
    if (app == null) {
        Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Symbol(R.drawable.ic_apps)
        }
    } else {
        Image(app.icon, null, modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    }
}

@Composable
fun AppStack(packages: List<String>, apps: List<InstalledApp>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        packages.take(3).forEach { name -> AppIcon(apps.firstOrNull { it.packageName == name }, Modifier.size(32.dp)) }
        if (packages.size > 3) Text("+${packages.size - 3}", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun MainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, @DrawableRes icon: Int? = null) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val active = enabled && !busy && LocalDestinationActive.current
    val scale = animateFloatAsState(if (pressed && active && !LocalReducedMotion.current) DesignTokens.pressScale else 1f,
        tween(if (pressed) DesignTokens.pressMs else DesignTokens.feedbackMs, easing = TimeOutEaseOut), label = "Primary action press")
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }, enabled = active,
        interactionSource = interactions, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else {
            if (icon != null) { Symbol(icon, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
fun ContextChoices(selected: LifeContext?, onSelect: (LifeContext) -> Unit, includeAll: Boolean = false, onAll: () -> Unit = {}) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (includeAll) FilterChip(selected == null, onAll, label = { Text("All") })
        LifeContext.entries.forEach { context ->
            FilterChip(selected == context, { onSelect(context) }, label = { Text(context.displayName()) },
                leadingIcon = { Symbol(context.icon(), modifier = Modifier.size(18.dp), tint = if (selected == context) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, bottom: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val snackbar = LocalPageSnackbar.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Symbol(R.drawable.ic_arrow_back, description = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = bottom,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        content = content,
    )
}

@Composable
fun BottomAction(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Hint(text: String, @DrawableRes icon: Int = R.drawable.ic_info) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Symbol(icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ActionRow(title: String, subtitle: String, @DrawableRes icon: Int, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Surface(onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Symbol(icon, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailing != null) trailing() else Symbol(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun appCount(count: Int) = if (count == 1) "1 app" else "$count apps"
fun minuteLabel(context: Context, minute: Int): String {
    val instant = LocalDate.of(2026, 1, 1).atStartOfDay().plusMinutes(minute.toLong()).atZone(ZoneId.systemDefault()).toInstant()
    return DateFormat.getTimeFormat(context).format(Date.from(instant))
}
fun timeLabel(context: Context, instant: Long): String = DateFormat.getTimeFormat(context).format(Date(instant))
fun dateLabel(context: Context, instant: Long): String = SimpleDateFormat("EEE, d MMM yyyy", context.resources.configuration.locales[0]).format(Date(instant))
fun shortTimeLabel(context: Context, instant: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val date = instant.inZone(zone).toLocalDate()
    val today = now.inZone(zone).toLocalDate()
    val prefix = if (date == today) "" else SimpleDateFormat(if (date.year == today.year) "EEE, d MMM · " else "d MMM yyyy · ", context.resources.configuration.locales[0]).format(Date(instant))
    return prefix + timeLabel(context, instant)
}
fun daysLabel(context: Context, days: Set<Int>): String = when (days) {
    (1..7).toSet() -> "Every day"
    (1..5).toSet() -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, context.resources.configuration.locales[0]) }
}
fun windowLabel(context: Context, window: TimeWindow) = "${minuteLabel(context, window.startMinute)}–${minuteLabel(context, window.endMinute)}${if (window.endsNextDay) " next day" else ""}"
fun ruleSummary(context: Context, rule: Rule): String = if (rule.kind == RuleKind.QUICK_BLOCK) "Temporary block"
    else "${if (rule.kind == RuleKind.ALLOW_ONLY) "Available only" else "Blocked"} ${rule.windows.joinToString(" and ") { windowLabel(context, it) }} · ${daysLabel(context, rule.days)}"
