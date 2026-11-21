package com.timeout.app.enforcement

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeout.app.MainActivity
import com.timeout.app.TimeOutApplication
import com.timeout.app.data.*
import com.timeout.app.ui.*
import com.timeout.app.ui.theme.LocalReducedMotion
import com.timeout.app.ui.theme.TimeOutEaseOut
import com.timeout.app.ui.theme.TimeOutTheme
import com.timeout.app.ui.theme.LocalReducedMotion
import com.timeout.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.roundToInt

internal enum class QuickStage { APPS, SELECTED, DURATION }
internal data class QuickBlockState(
    val apps: List<InstalledApp> = emptyList(), val recent: Set<String> = emptySet(), val loading: Boolean = true,
    val selected: InstalledApp? = null, val stage: QuickStage = QuickStage.APPS, val minutes: Int = 30,
    val busy: Boolean = false, val error: String? = null,
)
internal sealed interface QuickEvent { data object Finished : QuickEvent; data object OpenSetup : QuickEvent; data object OpenDraft : QuickEvent }

internal class QuickBlockViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TimeOutApplication).container
    private val repository = container.repository
    private val _state = MutableStateFlow(QuickBlockState())
    val state = _state.asStateFlow()
    private val channel = Channel<QuickEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var selectionJob: Job? = null

    init {
        viewModelScope.launch {
            val snapshot = repository.snapshot().rules
            val recent = snapshot.sortedByDescending { it.createdAt }.flatMap { it.packages }.take(8).toSet()
            val apps = withContext(Dispatchers.IO) { AppCatalog(application).load() }
                .sortedWith(compareBy<InstalledApp> { if (it.packageName in recent) 0 else 1 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            _state.value = QuickBlockState(apps = apps, recent = recent, loading = false)
        }
    }

    fun select(app: InstalledApp) {
        if (_state.value.busy) return
        selectionJob?.cancel()
        _state.update { it.copy(selected = app, stage = QuickStage.SELECTED, error = null) }
        selectionJob = viewModelScope.launch { delay(340); _state.update { it.copy(stage = QuickStage.DURATION) } }
    }
    fun backToApps() { selectionJob?.cancel(); _state.update { it.copy(selected = null, stage = QuickStage.APPS, error = null) } }
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
                    if (existing?.resumable == true) {
                        channel.send(QuickEvent.OpenDraft)
                    } else {
                        val checkpoint = DraftCheckpoint(draft, DraftScreen.SETUP, DraftScreen.SETUP, hasEdits = true,
                            revision = (existing?.revision ?: 0) + 1, updatedAt = now.wallTime)
                        container.draftStore.write(checkpoint)
                        channel.send(QuickEvent.OpenSetup)
                    }
                    return@launch
                }
                val rule = draft.toRule(now)
                repository.save(rule, now)
                container.protection.reconcile()
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
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra("open_draft", true))
                            finish()
                        }
                    }
                }
            }
            TimeOutTheme(appearance) {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = MaterialTheme.colorScheme.background) {
                        when (state.stage) {
                            QuickStage.APPS -> QuickAppPicker(state, vm::select)
                            QuickStage.SELECTED, QuickStage.DURATION -> QuickDuration(state, vm::backToApps, vm::setMinutes, vm::confirm)
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
    val haptic = LocalHapticFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.apps, query) { state.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var dropBounds by remember { mutableStateOf<Rect?>(null) }
    var dragging by remember { mutableStateOf<InstalledApp?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val hovering by remember { derivedStateOf { dragging != null && dropBounds?.contains(dragPosition) == true } }
    val targetColor by animateColorAsState(if (hovering) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        tween(160), label = "Drop target color")
    LaunchedEffect(hovering) { if (hovering) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).onGloballyPositioned { rootOrigin = it.positionInWindow() }) {
        Column(Modifier.fillMaxWidth()) {
            Text("Pause an app", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 76.dp)
                .onGloballyPositioned { dropBounds = it.boundsInWindow() }
                .border(1.dp, if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                color = targetColor, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Symbol(com.timeout.app.R.drawable.ic_brand_mark, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(if (hovering) "Release to choose" else "Drag an app here", style = MaterialTheme.typography.titleMedium)
                        Text("Or tap any app below", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), label = { Text("Search apps") },
                leadingIcon = { Symbol(com.timeout.app.R.drawable.ic_search) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Symbol(com.timeout.app.R.drawable.ic_close, description = "Clear search") } })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = rememberLazyListState(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!state.loading && filtered.isEmpty()) item(key = "empty") {
                    Text(if (query.isEmpty()) "No selectable apps" else "No apps match your search", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                items(filtered, key = { it.packageName }, contentType = { "app" }) { app ->
                    var rowOrigin by remember(app.packageName) { mutableStateOf(Offset.Zero) }
                    Surface(onClick = { onSelect(app) }, color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .onGloballyPositioned { rowOrigin = it.positionInWindow() }
                            .pointerInput(app.packageName, dropBounds) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point -> dragging = app; dragPosition = rowOrigin + point },
                                    onDrag = { change, amount -> change.consume(); dragPosition += amount },
                                    onDragEnd = {
                                        val accepted = dropBounds?.contains(dragPosition) == true
                                        dragging = null
                                        if (accepted) onSelect(app)
                                    },
                                    onDragCancel = { dragging = null },
                                )
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppIcon(app)
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                if (app.packageName in state.recent) Text("Recent", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Symbol(com.timeout.app.R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        dragging?.let { app ->
            Image(app.icon, null, Modifier.offset {
                IntOffset((dragPosition.x - rootOrigin.x - 28.dp.toPx()).roundToInt(), (dragPosition.y - rootOrigin.y - 28.dp.toPx()).roundToInt())
            }.size(56.dp).zIndex(4f).graphicsLayer { shadowElevation = 12.dp.toPx(); scaleX = 1.08f; scaleY = 1.08f }
                .clip(RoundedCornerShape(14.dp)))
        }
    }
}

@Composable
private fun QuickDuration(state: QuickBlockState, onBack: () -> Unit, onMinutes: (Int) -> Unit, onConfirm: () -> Unit) {
    val app = state.selected ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val show = state.stage == QuickStage.DURATION
    var custom by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntervalSelectionHero(app)
        Text(app.label, style = MaterialTheme.typography.titleLarge)
        AnimatedVisibility(show, enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = .97f)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AdaptiveChoices(listOf(15, 30, 45, 60, 90).map { ChoiceOption(it.toString(), "$it min") },
                    setOf(state.minutes.toString()), { onMinutes(it.toInt()) })
                OutlinedButton({ custom = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (state.minutes in listOf(15, 30, 45, 60, 90)) "Custom duration" else "Custom · ${durationText(state.minutes)}")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                MainButton("Block until ${timeLabel(context, System.currentTimeMillis() + state.minutes * 60_000L)}", onConfirm, busy = state.busy,
                    icon = com.timeout.app.R.drawable.ic_lock)
                TextButton(onBack, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp), enabled = !state.busy) { Text("Choose another app") }
            }
        }
    }
    if (custom) QuickDurationDialog(state.minutes, { custom = false }, { onMinutes(it); custom = false })
}

@Composable
private fun IntervalSelectionHero(app: InstalledApp) {
    val reduce = LocalReducedMotion.current
    val progress = remember(app.packageName) { Animatable(if (reduce) 1f else 0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(app.packageName, reduce) {
        if (!reduce) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(320, easing = TimeOutEaseOut))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Box(Modifier.size(196.dp, 112.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = 48.dp).size(42.dp, 92.dp)
            .graphicsLayer { translationX = if (reduce) 0f else -64.dp.toPx() * (1 - progress.value) }
            .clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.align(Alignment.CenterEnd).offset(x = (-48).dp, y = 8.dp).size(42.dp, 76.dp)
            .graphicsLayer { translationX = if (reduce) 0f else 64.dp.toPx() * (1 - progress.value) }
            .clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary))
        Image(app.icon, null, Modifier.size(58.dp).graphicsLayer {
            scaleX = .82f + .18f * progress.value; scaleY = scaleX; alpha = .7f + .3f * progress.value
        }.clip(RoundedCornerShape(14.dp)))
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
