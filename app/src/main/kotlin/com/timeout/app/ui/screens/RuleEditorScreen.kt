package com.timeout.app.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.timeout.app.R
import com.timeout.app.ui.*
import com.timeout.core.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RuleEditorScreen(vm: TimeOutViewModel, state: AppUiState, draft: RuleDraft, onBack: () -> Unit, onApps: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val quick = draft.kind == RuleKind.QUICK_BLOCK
    val preview by vm.preview.collectAsStateWithLifecycle()
    val currentPreview = preview.source == draft
    val error = preview.error.takeIf { currentPreview }
    val scroll = rememberLazyListState()
    val apps = remember(state.apps) { state.apps.associateBy { it.packageName } }
    val needsSetup = draft.enabled && !state.protectionReady
    var more by rememberSaveable(draft.id) { mutableStateOf(draft.revision > 0) }
    var confirmCommitment by rememberSaveable(draft.id) { mutableStateOf(false) }
    DetailScaffold(if (draft.revision > 0) "Edit routine" else if (quick) "Block now" else "Set a routine", onBack,
        bottom = {
            BottomAction {
                if (draft.packages.isNotEmpty() && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else if (draft.packages.isNotEmpty() && !currentPreview) Text("Updating access preview…", style = MaterialTheme.typography.bodyMedium)
                else if (draft.packages.isNotEmpty() && preview.rule != null) {
                    val rule = preview.rule!!
                    Text(if (quick && !draft.untilTime) "${appCount(draft.packages.size)} · ${draft.minutes} minutes after confirmation"
                        else if (quick) "${appCount(draft.packages.size)} · ends ${dateLabel(context, rule.endsAt)}, ${timeLabel(context, rule.endsAt)}"
                        else "${appCount(draft.packages.size)} · ${daysLabel(context, draft.days)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MainButton(when {
                    draft.packages.isEmpty() -> "Choose apps"
                    needsSetup -> "Set up blocking"
                    draft.enabled -> "Review and lock"
                    else -> "Save changes"
                }, onClick = {
                    when { draft.packages.isEmpty() -> onApps(); needsSetup -> onSetup(); draft.enabled -> confirmCommitment = true; else -> vm.saveDraft() }
                }, enabled = draft.packages.isEmpty() || (currentPreview && error == null), busy = state.busy,
                    icon = if (quick) R.drawable.ic_lock else R.drawable.ic_check)
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = scroll, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "intro", contentType = "intro") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (quick) "Choose your apps\nand time." else "Set your routine.", style = MaterialTheme.typography.headlineLarge)
            } }

            item(key = "context", contentType = "form") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Context")
                ContextChoices(draft.context, { next -> vm.updateDraft { it.copy(context = next) } })
            } }

            item(key = "apps", contentType = "action") {
                ActionRow("Apps", if (draft.packages.isEmpty()) "Choose the apps you want to pause" else "${appCount(draft.packages.size)} selected · tap to review", R.drawable.ic_apps, onApps,
                    trailing = { if (draft.packages.isNotEmpty()) AppStack(draft.packages.take(2), state.apps) else Symbol(R.drawable.ic_chevron_right) })
            }

            if (quick) {
                item(key = "duration", contentType = "form") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("How long?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.untilTime, { vm.updateDraft { it.copy(untilTime = false) } }, label = { Text("For a duration") })
                        FilterChip(draft.untilTime, { vm.updateDraft { it.copy(untilTime = true) } }, label = { Text("Until a time") })
                    }
                    if (!draft.untilTime) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 25, 45, 60, 90).forEach { minutes ->
                                FilterChip(draft.minutes == minutes, { vm.updateDraft { it.copy(minutes = minutes) } }, label = { Text("$minutes min") })
                            }
                        }
                        DurationButton(draft.minutes) { value -> vm.updateDraft { it.copy(minutes = value) } }
                    } else {
                        EndTimeButton(draft.untilMinute, draft.untilTomorrow) { minute, tomorrow ->
                            vm.updateDraft { it.copy(untilMinute = minute, untilTomorrow = tomorrow) }
                        }
                    }
                }
                }
            } else {
                item(key = "mode", contentType = "form") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("How should it work?")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.kind == RuleKind.BLOCK_DURING, { vm.updateDraft { it.copy(kind = RuleKind.BLOCK_DURING) } }, label = { Text("Protect these hours") })
                        FilterChip(draft.kind == RuleKind.ALLOW_ONLY, { vm.updateDraft { it.copy(kind = RuleKind.ALLOW_ONLY) } }, label = { Text("Only allow these hours") })
                    }
                    Text(if (draft.kind == RuleKind.BLOCK_DURING) "Selected apps are blocked during these periods. Outside them, this routine adds no restriction."
                        else "Selected apps are available only during these windows on the days you choose.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
                itemsIndexed(draft.windows, key = { index, _ -> "window:$index" }, contentType = { _, _ -> "window" }) { index, window ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (draft.windows.size == 1) "Hours" else "Window ${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (draft.windows.size > 1) IconButton({ vm.updateDraft { it.copy(windows = it.windows.filterIndexed { i, _ -> i != index }) } }) {
                                Symbol(R.drawable.ic_close, description = "Remove window ${index + 1}")
                            }
                        }
                        TimeWindowButton(window, index) { value ->
                            vm.updateDraft { it.copy(windows = it.windows.mapIndexed { i, w -> if (i == index) value else w }) }
                        }
                    }
                }
                if (draft.windows.size < 8) item(key = "add-window", contentType = "action") { TextButton({ vm.updateDraft { it.copy(windows = it.windows + TimeWindow(19 * 60, 20 * 60)) } }) {
                    Symbol(R.drawable.ic_add, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Add a window")
                } }
                item(key = "days", contentType = "form") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle("Repeat on")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { day ->
                            FilterChip(day in draft.days, { vm.updateDraft { it.copy(days = if (day in it.days) it.days - day else it.days + day) } },
                                label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale)) })
                        }
                    }
                    TextButton({ vm.updateDraft { it.copy(days = if (it.days.size == 7) (1..5).toSet() else (1..7).toSet()) } }) {
                        Text(if (draft.days.size == 7) "Use weekdays" else "Use every day")
                    }
                } }
            }

            item(key = "commitment", contentType = "form") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(if (quick) "Locked until your timer ends" else "Choose your commitment")
                if (!quick) CommitmentDateButton(draft) { epochDay -> vm.updateDraft { it.copy(commitmentDateEpochDay = epochDay) } }
                Text(if (quick) "No early stop in Time Out."
                    else "Editable after this date. The routine keeps running.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            item(key = "name-toggle", contentType = "action") { TextButton({ more = !more }) {
                Symbol(R.drawable.ic_tune, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(if (more) "Hide name" else "Give it a name")
            } }
            if (more) item(key = "name", contentType = "form") {
                OutlinedTextField(draft.name, { name -> vm.updateDraft { it.copy(name = name.take(80)) } }, label = { Text("Name (optional)") },
                    placeholder = { Text("${draft.context.displayName()} ${if (quick) "session" else "routine"}") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }

            item(key = "review", contentType = "review") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Hint("Keep essential apps available.")
                if (!quick) {
                    preview.rule?.takeIf { currentPreview }?.let { Text(ruleSummary(context, it), style = MaterialTheme.typography.bodyLarge) }
                    Text(draft.zoneId.replace('_', ' '), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (currentPreview && preview.overlapCount > 0) Hint("${preview.overlapCount} other plans also apply.", R.drawable.ic_shield)
                }
                if (!draft.enabled) Hint("This routine is off. Saving changes will keep it off.")
            } }
            if (draft.packages.isNotEmpty() && currentPreview && error == null) {
                item(key = "preview-title", contentType = "heading") { SectionTitle("What happens next") }
                items(preview.apps, key = { "preview:${it.packageName}" }, contentType = { "app-preview" }) { access ->
                        val app = apps[access.packageName]
                        val decision = access.decision
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(app)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app?.label ?: access.packageName, style = MaterialTheme.typography.titleMedium)
                                val next = decision.nextAccessAt
                                Text(when {
                                    !decision.restricted -> "Time Out would allow access now."
                                    next != null -> "Planned access: ${dateLabel(context, next)}, ${timeLabel(context, next)}"
                                    else -> "These plans leave no shared access window in the next two weeks. Review the overlap."
                                }, style = MaterialTheme.typography.bodyMedium, color = if (decision.restricted && next == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (decision.restrictions.size > 1) Text("Rules: ${decision.restrictions.joinToString { it.rule.name }}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                }
            }
            item(key = "end", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
        }
    }
    if (confirmCommitment) {
        val rule = vm.draftRule()
        val end = CommitmentPolicy.endsAt(rule, state.clock)
        AlertDialog(onDismissRequest = { confirmCommitment = false },
            icon = { Symbol(R.drawable.ic_lock, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(if (quick) "Lock this session?" else "Lock this routine?") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${if (quick) "Session ends" else "Settings unlock"} ${dateLabel(context, end)}, ${timeLabel(context, end)}.")
                    Text("Apps: ${draft.packages.joinToString { name -> state.apps.firstOrNull { it.packageName == name }?.label ?: name }}")
                    if (!quick) Text(ruleSummary(context, rule))
                    Text("No early stop or edits in Time Out.")
                }
            },
            confirmButton = { TextButton({ confirmCommitment = false; vm.saveDraft() }, enabled = currentPreview && error == null && !state.busy) { Text("Lock and start") } },
            dismissButton = { TextButton({ confirmCommitment = false }) { Text("Keep reviewing") } })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CommitmentDateButton(draft: RuleDraft, onChange: (Long) -> Unit) {
    val zone = ZoneId.of(draft.zoneId)
    val today = LocalDate.now(zone)
    val context = LocalContext.current
    val end = LocalDate.ofEpochDay(draft.commitmentDateEpochDay).atStartOfDay(zone).toInstant().toEpochMilli()
    var show by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7L, 14L, 30L).forEach { days ->
            FilterChip(draft.commitmentDateEpochDay == today.plusDays(days).toEpochDay(), { onChange(today.plusDays(days).toEpochDay()) }, label = { Text("$days days") })
        }
    }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Symbol(R.drawable.ic_calendar_today, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("Unlock settings ${dateLabel(context, end)}, ${timeLabel(context, end)}")
    }
    if (show) {
        val selectable = remember(today) { object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() > today
            override fun isSelectableYear(year: Int): Boolean = year >= today.year
        } }
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(draft.commitmentDateEpochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialDisplayMode = if (LocalConfiguration.current.fontScale > 1.3f) DisplayMode.Input else DisplayMode.Picker, selectableDates = selectable)
        DatePickerDialog(onDismissRequest = { show = false },
            confirmButton = { TextButton({
                picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                show = false
            }, enabled = picker.selectedDateMillis?.let(selectable::isSelectableDate) == true) { Text("Set date") } },
            dismissButton = { TextButton({ show = false }) { Text("Cancel") } }) {
            DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}

private fun durationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} hr"
    else -> "${minutes / 60} hr ${minutes % 60} min"
}

@Composable
private fun DurationButton(minutes: Int, onChange: (Int) -> Unit) {
    var show by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Symbol(R.drawable.ic_schedule, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(if (minutes in listOf(15, 25, 45, 60, 90)) "Custom duration" else "Custom · ${durationLabel(minutes)}")
    }
    if (show) {
        var hours by rememberSaveable(minutes) { mutableStateOf((minutes / 60).toString()) }
        var remainder by rememberSaveable(minutes) { mutableStateOf((minutes % 60).toString()) }
        val hourValue = hours.toIntOrNull()
        val minuteValue = remainder.toIntOrNull()
        val total = if (hourValue != null && minuteValue != null) hourValue * 60 + minuteValue else 0
        val valid = hourValue != null && minuteValue != null && hourValue in 0..24 && minuteValue in 0..59 && total in 1..1440
        AlertDialog(onDismissRequest = { show = false }, title = { Text("Set duration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(hours, { value -> if (value.length <= 2 && value.all(Char::isDigit)) hours = value },
                            Modifier.weight(1f), label = { Text("Hours") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(remainder, { value -> if (value.length <= 2 && value.all(Char::isDigit)) remainder = value },
                            Modifier.weight(1f), label = { Text("Minutes") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text(if (valid) durationLabel(total) else "Choose 1 minute to 24 hours.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton({ onChange(total); show = false }, enabled = valid) { Text("Set duration") } },
            dismissButton = { TextButton({ show = false }) { Text("Cancel") } })
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EndTimeButton(minute: Int, tomorrow: Boolean, onChange: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    var show by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ show = true }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Symbol(R.drawable.ic_schedule, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp)); Text("${if (tomorrow) "Tomorrow" else "Today"} · ${minuteLabel(context, minute)}")
    }
    if (show) {
        val picker = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = DateFormat.is24HourFormat(context))
        val configuration = LocalConfiguration.current
        var input by rememberSaveable { mutableStateOf(configuration.fontScale > 1.3f) }
        var nextDay by rememberSaveable(tomorrow) { mutableStateOf(tomorrow) }
        Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
                Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ends at", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!nextDay, { nextDay = false }, label = { Text("Today") })
                        FilterChip(nextDay, { nextDay = true }, label = { Text("Tomorrow") })
                    }
                    if (input) TimeInput(picker) else TimePicker(picker)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ input = !input }) { Text(if (input) "Use clock" else "Type time") }
                        Row {
                            TextButton({ show = false }) { Text("Cancel") }
                            TextButton({ onChange(picker.hour * 60 + picker.minute, nextDay); show = false }) { Text("Set end") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TimeWindowButton(window: TimeWindow, index: Int, onChange: (TimeWindow) -> Unit) {
    val context = LocalContext.current
    var show by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text("${minuteLabel(context, window.startMinute)} – ${minuteLabel(context, window.endMinute)}", style = MaterialTheme.typography.titleMedium)
            Text(if (window.endsNextDay) "Ends next day" else "Same day", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Symbol(R.drawable.ic_edit, description = "Edit window ${index + 1}", tint = MaterialTheme.colorScheme.primary)
    }
    if (show) {
        val is24 = DateFormat.is24HourFormat(context)
        val start = rememberTimePickerState(window.startMinute / 60, window.startMinute % 60, is24)
        val end = rememberTimePickerState(window.endMinute / 60, window.endMinute % 60, is24)
        var editingStart by rememberSaveable { mutableStateOf(true) }
        var nextDay by rememberSaveable(window.endsNextDay) { mutableStateOf(window.endsNextDay) }
        val configuration = LocalConfiguration.current
        var input by rememberSaveable { mutableStateOf(configuration.fontScale > 1.3f) }
        val startMinute = start.hour * 60 + start.minute
        val endMinute = end.hour * 60 + end.minute
        val duration = TimeWindow(startMinute, endMinute, nextDay).durationMinutes()
        val valid = duration in 1..1440
        Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
                Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Edit window ${index + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(editingStart, { editingStart = true }, label = { Text("Start · ${minuteLabel(context, startMinute)}") })
                        FilterChip(!editingStart, { editingStart = false }, label = { Text("End · ${minuteLabel(context, endMinute)}") })
                    }
                    if (input) TimeInput(if (editingStart) start else end) else TimePicker(if (editingStart) start else end)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!nextDay, { nextDay = false }, label = { Text("Same day") })
                        FilterChip(nextDay, { nextDay = true }, label = { Text("Next day") })
                    }
                    Text(if (valid) durationLabel(duration) else if (!nextDay) "End must be after start, or choose Next day."
                        else "A window cannot exceed 24 hours.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ input = !input }) { Text(if (input) "Use clock" else "Type time") }
                        Row {
                            TextButton({ show = false }) { Text("Cancel") }
                            TextButton({ onChange(TimeWindow(startMinute, endMinute, nextDay)); show = false }, enabled = valid) { Text("Set window") }
                        }
                    }
                }
            }
        }
    }
}
