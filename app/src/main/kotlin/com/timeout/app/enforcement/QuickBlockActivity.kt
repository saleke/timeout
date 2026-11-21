package com.timeout.app.enforcement

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeout.app.MainActivity
import com.timeout.app.R
import com.timeout.app.TimeOutApplication
import com.timeout.app.data.*
import com.timeout.app.ui.*
import com.timeout.app.ui.theme.LocalReducedMotion
import com.timeout.app.ui.theme.TimeOutEaseOut
import com.timeout.app.ui.theme.TimeOutTheme
import com.timeout.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class QuickStage { APPS, DURATION }
internal data class QuickBlockState(
    val apps: List<InstalledApp> = emptyList(), val recent: Set<String> = emptySet(), val loading: Boolean = true,
    val selected: InstalledApp? = null, val stage: QuickStage = QuickStage.APPS, val minutes: Int = 30,
    val busy: Boolean = false, val error: String? = null,
)
internal sealed interface QuickEvent { data object Finished : QuickEvent; data object OpenSetup : QuickEvent; data object OpenDraft : QuickEvent }

internal class QuickBlockViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TimeOutApplication).container
    private val repository = container.repository
    private val _state = MutableStateFlow(QuickBlockState(minutes = container.preferences.getInt("quick_duration_minutes", 30).coerceIn(1, 1440)))
    val state = _state.asStateFlow()
    private val channel = Channel<QuickEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val snapshot = repository.snapshot().rules
            val recent = snapshot.sortedByDescending { it.createdAt }.flatMap { it.packages }.take(8).toSet()
            val apps = withContext(Dispatchers.IO) { AppCatalog(application).load() }
                .sortedWith(compareBy<InstalledApp> { if (it.packageName in recent) 0 else 1 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            _state.update { it.copy(apps = apps, recent = recent, loading = false) }
        }
    }

    fun select(app: InstalledApp) {
        if (!_state.value.busy) _state.update { it.copy(selected = app, stage = QuickStage.DURATION, error = null) }
    }
    fun backToApps() { if (!_state.value.busy) _state.update { it.copy(selected = null, stage = QuickStage.APPS, error = null) } }
    fun setMinutes(value: Int) { if (value in 1..1440 && !_state.value.busy) _state.update { it.copy(minutes = value) } }

    fun confirm() {
        val selected = _state.value.selected ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val now = container.clock()
                val draft = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.PERSONAL, now)
                    .copy(packages = setOf(selected.packageName), minutes = _state.value.minutes)
                if (!runCatching { container.protection.requireReady() }.isSuccess) {
                    val existing = container.draftStore.read()
                    if (existing?.resumable == true) channel.send(QuickEvent.OpenDraft)
                    else {
                        container.draftStore.write(DraftCheckpoint(draft, DraftScreen.SETUP, DraftScreen.SETUP, hasEdits = true,
                            revision = (existing?.revision ?: 0) + 1, updatedAt = now.wallTime))
                        channel.send(QuickEvent.OpenSetup)
                    }
                    return@launch
                }
                repository.save(draft.toRule(now), now)
                container.protection.reconcile()
                container.preferences.edit { putInt("quick_duration_minutes", _state.value.minutes) }
                channel.send(QuickEvent.Finished)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _state.update { it.copy(error = failure.message ?: "This block could not be started.") }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
class QuickBlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as TimeOutApplication).container
            val appearance by container.appearance.collectAsStateWithLifecycle()
            val reduced by container.reducedMotion.collectAsStateWithLifecycle()
            val vm: QuickBlockViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.events.collect { event ->
                    when (event) {
                        QuickEvent.Finished -> finish()
                        QuickEvent.OpenSetup, QuickEvent.OpenDraft -> {
                            startActivity(Intent(this@QuickBlockActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_draft", true))
                            finish()
                        }
                    }
                }
            }
            TimeOutTheme(appearance) {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                        containerColor = MaterialTheme.colorScheme.background) {
                        when (state.stage) {
                            QuickStage.APPS -> QuickAppPicker(state, vm::select)
                            QuickStage.DURATION -> QuickDuration(state, vm::backToApps, vm::setMinutes, vm::confirm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAppPicker(state: QuickBlockState, onSelect: (InstalledApp) -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.apps, query) { state.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    val recent = remember(state.apps, state.recent) { state.apps.filter { it.packageName in state.recent }.take(5) }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
        Text("Pause an app", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (recent.isNotEmpty() && query.isEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { app ->
                    Surface(onClick = { onSelect(app) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.width(82.dp).heightIn(min = 82.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppIcon(app)
                            Text(app.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            label = { Text("Search apps") }, leadingIcon = { Symbol(R.drawable.ic_search) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Symbol(R.drawable.ic_close, description = "Clear search") } })
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp), state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && filtered.isEmpty()) item(key = "empty") {
                Text(if (query.isEmpty()) "No selectable apps" else "No apps match your search", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 24.dp))
            }
            items(filtered, key = { it.packageName }, contentType = { "app" }) { app ->
                Surface(onClick = { onSelect(app) }, color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppIcon(app)
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Symbol(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDuration(state: QuickBlockState, onBack: () -> Unit, onMinutes: (Int) -> Unit, onConfirm: () -> Unit) {
    val app = state.selected ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    var custom by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CompactSelectionHeader(app)
        AdaptiveChoices(listOf(15, 30, 45, 60, 90).map { ChoiceOption(it.toString(), "$it min") },
            setOf(state.minutes.toString()), { onMinutes(it.toInt()) })
        OutlinedButton({ custom = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (state.minutes in listOf(15, 30, 45, 60, 90)) "Custom duration" else "Custom · ${durationText(state.minutes)}")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        MainButton("Block until ${timeLabel(context, System.currentTimeMillis() + state.minutes * 60_000L)}", onConfirm, busy = state.busy, icon = R.drawable.ic_lock)
        TextButton(onBack, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp), enabled = !state.busy) { Text("Choose another app") }
    }
    if (custom) QuickDurationDialog(state.minutes, { custom = false }, { onMinutes(it); custom = false })
}

@Composable
private fun CompactSelectionHeader(app: InstalledApp) {
    val reduce = LocalReducedMotion.current
    val progress = remember(app.packageName) { Animatable(if (reduce) 1f else 0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(app.packageName, reduce) {
        if (!reduce) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(220, easing = TimeOutEaseOut))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(app.icon, null, Modifier.size(48.dp).graphicsLayer { scaleX = .9f + .1f * progress.value; scaleY = scaleX }
                .clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text("Choose duration", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Row(Modifier.width(34.dp).height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(14.dp).height(44.dp).graphicsLayer { translationX = if (reduce) 0f else -12.dp.toPx() * (1 - progress.value) }
                    .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary))
                Box(Modifier.width(14.dp).height(36.dp).graphicsLayer { translationX = if (reduce) 0f else 12.dp.toPx() * (1 - progress.value) }
                    .clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

private fun durationText(minutes: Int) = if (minutes < 60) "$minutes min" else if (minutes % 60 == 0) "${minutes / 60} hr" else "${minutes / 60} hr ${minutes % 60} min"

@Composable
private fun QuickDurationDialog(current: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var hours by rememberSaveable(current) { mutableStateOf((current / 60).toString()) }
    var minutes by rememberSaveable(current) { mutableStateOf((current % 60).toString()) }
    val h = hours.toIntOrNull(); val m = minutes.toIntOrNull(); val total = if (h != null && m != null) h * 60 + m else 0
    val valid = h != null && m != null && h in 0..24 && m in 0..59 && total in 1..1440
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Custom duration") }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(hours, { if (it.length <= 2 && it.all(Char::isDigit)) hours = it }, Modifier.weight(1f), label = { Text("Hours") },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
            OutlinedTextField(minutes, { if (it.length <= 2 && it.all(Char::isDigit)) minutes = it }, Modifier.weight(1f), label = { Text("Minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
        }
    }, confirmButton = { TextButton({ onSet(total) }, enabled = valid) { Text("Set") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
