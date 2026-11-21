package com.timeout.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.timeout.app.TimeOutApplication
import com.timeout.app.ProtectionRuntime
import com.timeout.app.ProtectionMode
import com.timeout.app.data.AppCatalog
import com.timeout.app.data.HistoryRecord
import com.timeout.app.data.InstalledApp
import com.timeout.app.data.commitOrThrow
import com.timeout.app.data.PendingChange
import com.timeout.app.data.DraftCheckpoint
import com.timeout.app.data.DraftScreen
import com.timeout.app.data.restoredDraftCheckpoint
import com.timeout.app.data.reconcileSavedRules
import com.timeout.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlin.math.abs

data class AppUiState(
    val loading: Boolean = true,
    val rulesLoaded: Boolean = false,
    val draftLoaded: Boolean = false,
    val draftPersistenceError: String? = null,
    val rules: List<Rule> = emptyList(),
    val history: List<HistoryRecord> = emptyList(),
    val changes: List<PendingChange> = emptyList(),
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = true,
    val appsError: String? = null,
    val deviceOwner: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val disclosureAccepted: Boolean = false,
    val runtime: ProtectionRuntime = ProtectionRuntime(),
    val clock: ClockSnapshot = ClockSnapshot(System.currentTimeMillis()),
    val policy: PolicyOverview = PolicyOverview(ClockSnapshot(System.currentTimeMillis())),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val protectionReady: Boolean get() = disclosureAccepted && runtime.connected && runtime.configurationLoaded && runtime.error == null && error == null &&
        if (runtime.mode == ProtectionMode.MANAGED) deviceOwner && runtime.exactAlarms else accessibilityEnabled
    val configurationCurrent: Boolean get() = rules.filter { it.enabled }.all { "${it.id}:${it.revision}" in runtime.configuredKeys }
    val lockedRules: List<Rule> get() = rules.filter { CommitmentPolicy.isLocked(it, clock) }
}

data class DraftAppAccess(val packageName: String, val decision: AccessDecision)
data class DraftPreview(
    val source: RuleDraft? = null,
    val rule: Rule? = null,
    val error: String? = null,
    val apps: List<DraftAppAccess> = emptyList(),
    val overlapCount: Int = 0,
)

data class DraftRecovery(val draftId: String, val screen: DraftScreen)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Saved(val text: String, val pendingRuleId: String? = null) : UiEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimeOutViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val container = (application as TimeOutApplication).container
    private val repository = container.repository
    private val catalog = AppCatalog(application)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(AppUiState())
    val state = _state.asStateFlow()
    private val _draft = MutableStateFlow(RuleDraft())
    val draft = _draft.asStateFlow()
    private val _checkpoint = MutableStateFlow(DraftCheckpoint())
    val draftCheckpoint = _checkpoint.asStateFlow()
    private val _draftRecovery = MutableStateFlow<DraftRecovery?>(null)
    val draftRecovery = _draftRecovery.asStateFlow()
    private val _replacementDraft = MutableStateFlow<RuleDraft?>(null)
    val replacementDraft = _replacementDraft.asStateFlow()
    private var settingsPaused = false
    private val _clock = MutableStateFlow(container.clock())
    val clock = _clock.asStateFlow()
    private val policyTime = MutableStateFlow(container.clock())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val preview = combine(_draft, _state.map { it.rules }.distinctUntilChanged(), policyTime) { draft, rules, now -> Triple(draft, rules, now) }
        .mapLatest { (draft, rules, _) ->
            withContext(Dispatchers.Default) {
                try { createPreview(draft, rules, container.clock()) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    DraftPreview(source = draft, error = "This preview could not be prepared. Check the saved time zone and selected times.")
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DraftPreview())
    private val channel = Channel<UiEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var clockJob: Job? = null
    private var appLoadJob: Job? = null

    init {
        viewModelScope.launch {
            var restored = DraftCheckpoint()
            var problem: String? = null
            val transient = savedState.get<String>("draft_checkpoint")?.let { runCatching { json.decodeFromString<DraftCheckpoint>(it) }.getOrNull() }
            val legacy = savedState.get<String>("draft")?.let { runCatching { json.decodeFromString<RuleDraft>(it) }.getOrNull() }
            var durable: DraftCheckpoint? = null
            try {
                durable = container.draftStore.read()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                problem = "Your saved draft could not be fully restored."
            }
            restored = restoredDraftCheckpoint(durable, transient, legacy)
            try {
                restored = restored.reconcileSavedRules(repository.snapshot().rules)
                if (restored != durable && restored.revision > 0) container.draftStore.write(restored)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                problem = "Your draft is restored, but its checkpoint needs a retry."
            }
            publishCheckpoint(restored, persist = false)
            restored.draft?.let { source -> restored.settingsReturn?.let { _draftRecovery.value = DraftRecovery(source.id, it) } }
            _state.update { it.copy(draftLoaded = true, draftPersistenceError = problem,
                loading = !it.rulesLoaded || it.policy.sourceRules != it.rules) }
        }
        viewModelScope.launch {
            repository.state.catch { _state.update { it.copy(loading = !it.draftLoaded, rulesLoaded = true, error = "Saved protection could not be loaded. Its records have been preserved.") } }
                .collect { value -> _state.update { it.copy(rulesLoaded = true, rules = value.rules,
                    error = if (value.unreadable > 0) "Some saved protection could not be read. Review Settings before relying on it." else null) } }
        }
        viewModelScope.launch { repository.pruneHistory(container.clock()); repository.history.collect { h -> _state.update { it.copy(history = h) } } }
        viewModelScope.launch { repository.changes.collect { c -> _state.update { it.copy(changes = c) } } }
        viewModelScope.launch { container.runtime.collect { r -> _state.update { it.copy(runtime = r) } } }
        viewModelScope.launch {
            combine(_state.map { it.rulesLoaded to it.rules }.distinctUntilChanged().filter { it.first }, policyTime) { (_, rules), now -> rules to now }
                .mapLatest { (rules, now) -> withContext(Dispatchers.Default) { PolicyOverview.evaluate(rules, now) } }
                .collect { overview -> _state.update { if (it.rules == overview.sourceRules) it.copy(loading = !it.draftLoaded, policy = overview, clock = overview.clock) else it } }
        }
        reloadApps()
        refresh()
    }

    fun reloadApps() {
        if (appLoadJob?.isActive == true) return
        _state.update { it.copy(appsLoading = true, appsError = null) }
        appLoadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.load() } }
            _state.update { it.copy(apps = result.getOrElse { _ -> it.apps }, appsLoading = false,
                appsError = if (result.isFailure) "Your app list could not be loaded. Refresh to try again." else null) }
        }
    }

    fun setUiActive(active: Boolean) {
        clockJob?.cancel()
        clockJob = null
        if (!active) {
            settingsPaused = _checkpoint.value.settingsReturn != null
            persistCheckpoint(_checkpoint.value)
        }
        if (active) {
            refresh()
            if (settingsPaused) {
                val checkpoint = _checkpoint.value
                checkpoint.draft?.let { source -> checkpoint.settingsReturn?.let { _draftRecovery.value = DraftRecovery(source.id, it) } }
                settingsPaused = false
                reloadApps()
            }
            clockJob = viewModelScope.launch {
                repository.completeExpired(container.clock())
                repository.pruneHistory(container.clock())
                while (isActive) {
                    val now = container.clock()
                    val before = _clock.value
                    _clock.value = now
                    val evaluated = _state.value.policy
                    val clockShift = abs((now.wallTime - before.wallTime) - (now.elapsedTime - before.elapsedTime)) > 1500
                    val boundaryReached = evaluated.nextBoundaryAt?.let { now.wallTime >= it } == true
                    if (clockShift || boundaryReached || now.wallTime / 60_000 != evaluated.clock.wallTime / 60_000) {
                        if (boundaryReached) repository.completeExpired(now)
                        policyTime.value = now
                    }
                    delay(1000 - now.elapsedTime % 1000)
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(deviceOwner = container.managed.isDeviceOwner(),
            accessibilityEnabled = container.protection.accessibilityEnabled(), disclosureAccepted = container.protection.disclosureAccepted()) }
        val now = container.clock()
        _clock.value = now
        policyTime.value = now
        container.refreshSignals.tryEmit(Unit)
    }

    fun setupMessage(message: String) { channel.trySend(UiEvent.Message(message)) }

    fun beginDraft(kind: RuleKind, context: LifeContext = LifeContext.STUDY, rule: Rule? = null): Boolean {
        if (!_state.value.draftLoaded || _state.value.busy) return false
        val saved = rule?.let { candidate -> _state.value.rules.firstOrNull { it.id == candidate.id } }
        if (saved != null && CommitmentPolicy.isLocked(saved, container.clock())) {
            channel.trySend(UiEvent.Message("This commitment cannot be edited before its confirmed end."))
            return false
        }
        val next = if (rule != null) RuleDraft(rule.id, rule.name, rule.context, rule.kind, rule.packages.toSet(),
            days = rule.days, windows = rule.windows, commitment = Commitment.LOCKED, createdAt = rule.createdAt,
            revision = rule.revision, zoneId = rule.zoneId, enabled = rule.enabled)
        else RuleDraft.fresh(kind, context, container.clock())
        if (_checkpoint.value.resumable && _checkpoint.value.draft?.id != next.id) {
            _replacementDraft.value = next
            return false
        }
        replaceDraft(next)
        return true
    }

    fun updateDraft(change: (RuleDraft) -> RuleDraft) {
        if (_state.value.busy) return
        val previous = _checkpoint.value.draft ?: return
        var next = change(previous)
        if (next.untilTime && !previous.untilTime) next = next.chooseUntil(next.untilMinute, next.untilTomorrow, container.clock())
        if (next != previous) editCheckpoint { it.copy(draft = next, hasEdits = true) }
    }

    fun chooseUntil(minute: Int, tomorrow: Boolean) = updateDraft { it.chooseUntil(minute, tomorrow, container.clock()) }

    private fun replaceDraft(draft: RuleDraft) {
        editCheckpoint { it.copy(draft = draft, screen = DraftScreen.BUILDER, settingsReturn = null, hasEdits = false, saving = false) }
    }

    fun confirmReplacement(): Boolean {
        val next = _replacementDraft.value ?: return false
        if (_state.value.busy) return false
        _replacementDraft.value = null
        replaceDraft(next)
        return true
    }
    fun cancelReplacement() { _replacementDraft.value = null }
    fun resumeDraft(): Boolean {
        if (_checkpoint.value.draft == null || _state.value.busy) return false
        _replacementDraft.value = null
        editCheckpoint { it.copy(screen = DraftScreen.BUILDER, settingsReturn = null) }
        return true
    }
    fun discardDraft() {
        if (!_state.value.busy) editCheckpoint { it.copy(draft = null, hasEdits = false, settingsReturn = null, saving = false) }
    }
    fun recordDraftScreen(screen: DraftScreen) {
        if (_checkpoint.value.draft != null && _checkpoint.value.screen != screen) editCheckpoint { it.copy(screen = screen) }
    }
    fun completeDraftRecovery(request: DraftRecovery) {
        if (_draftRecovery.value != request) return
        if (_checkpoint.value.draft?.id == request.draftId)
            editCheckpoint { it.copy(screen = request.screen, settingsReturn = null) }
        _draftRecovery.value = null
    }
    suspend fun restoreLatestCheckpoint(): DraftCheckpoint {
        val durable = container.draftStore.read()
        var restored = restoredDraftCheckpoint(durable, _checkpoint.value, null).reconcileSavedRules(repository.snapshot().rules)
        if (restored.revision == 0L && restored.draft != null) restored = restored.copy(revision = 1)
        if (restored.revision >= _checkpoint.value.revision) publishCheckpoint(restored, persist = false)
        restored.draft?.let { source -> restored.settingsReturn?.let { _draftRecovery.value = DraftRecovery(source.id, it) } }
        return restored
    }

    private fun editCheckpoint(change: (DraftCheckpoint) -> DraftCheckpoint): DraftCheckpoint {
        val current = _checkpoint.value
        return change(current).copy(revision = current.revision + 1, updatedAt = container.clock().wallTime).also { publishCheckpoint(it) }
    }
    private fun publishCheckpoint(checkpoint: DraftCheckpoint, persist: Boolean = true) {
        _checkpoint.value = checkpoint
        checkpoint.draft?.let { _draft.value = it }
        savedState["draft_checkpoint"] = json.encodeToString(checkpoint)
        savedState.remove<String>("draft")
        if (persist) persistCheckpoint(checkpoint)
    }
    private fun persistCheckpoint(checkpoint: DraftCheckpoint) {
        if (checkpoint.revision <= 0) return
        container.scope.launch {
            try {
                container.draftStore.write(checkpoint)
                if (_checkpoint.value.revision == checkpoint.revision) _state.update { it.copy(draftPersistenceError = null) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (_checkpoint.value.revision == checkpoint.revision) _state.update { it.copy(draftPersistenceError = "Draft not saved yet. Retry before leaving for setup.") }
            }
        }
    }
    private suspend fun checkpointSettings(fromDraft: Boolean, returnTo: DraftScreen) {
        if (!fromDraft) return
        check(_checkpoint.value.draft != null) { "Return to your draft before opening setup." }
        editCheckpoint { it.copy(screen = DraftScreen.SETUP, settingsReturn = returnTo) }
        flushDraft()
    }
    private suspend fun flushDraft() {
        do {
            val checkpoint = _checkpoint.value
            container.draftStore.write(checkpoint)
        } while (_checkpoint.value.revision != checkpoint.revision)
        _state.update { it.copy(draftPersistenceError = null) }
    }
    fun openSettings(fromDraft: Boolean, returnTo: DraftScreen, launch: () -> Unit) = action {
        checkpointSettings(fromDraft, returnTo)
        launch()
    }
    fun cancelSettingsHandoff() {
        if (_checkpoint.value.settingsReturn != null) editCheckpoint { it.copy(settingsReturn = null) }
    }

    fun toggleApp(packageName: String) = updateDraft {
        it.copy(packages = if (packageName in it.packages) it.packages - packageName else it.packages + packageName)
    }

    fun draftRule(enabled: Boolean = true): Rule = ruleFrom(_draft.value, container.clock(), enabled)

    private fun ruleFrom(draft: RuleDraft, clock: ClockSnapshot, enabled: Boolean = true): Rule = draft.toRule(clock, enabled)

    private fun draftError(d: RuleDraft, rule: Rule, rules: List<Rule>, now: ClockSnapshot): String? {
        if (d.kind == RuleKind.QUICK_BLOCK && d.minutes !in 1..1440 && !d.untilTime) return "Choose between 1 minute and 24 hours."
        val previous = rules.firstOrNull { it.id == rule.id }
        if (d.untilTime && d.untilDateEpochDay == null) return "Confirm the end date and time."
        if (previous != null && d.revision > 0 && previous.revision != d.revision) return "This plan changed. Reopen it before editing."
        if (previous != null && CommitmentPolicy.isLocked(previous, now)) return "This commitment is locked until its confirmed end."
        if (d.kind == RuleKind.QUICK_BLOCK && (rule.endsAt <= rule.startedAt || rule.endsAt - rule.startedAt > 86_400_000)) return "Choose an end in the next 24 hours."
        return RuleEngine.validate(rule) ?: CommitmentPolicy.activationError(rule.copy(enabled = d.enabled), now) ?:
            RuleEngine.overlapError(rules.filter { it.id != rule.id } + rule.copy(enabled = d.enabled), now)
    }

    private suspend fun createPreview(draft: RuleDraft, rules: List<Rule>, now: ClockSnapshot): DraftPreview {
        val rule = ruleFrom(draft, now, draft.enabled)
        val error = draftError(draft, rule, rules, now)
        if (error != null) return DraftPreview(draft, rule, error)
        val prospective = rules.filter { it.id != rule.id } + rule
        val overview = PolicyOverview.evaluate(prospective, now)
        val apps = draft.packages.map { name ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            DraftAppAccess(name, overview.decisions[name] ?: AccessDecision(emptyList(), null))
        }
        return DraftPreview(draft, rule, apps = apps,
            overlapCount = rules.count { it.id != rule.id && it.enabled && it.packages.any(draft.packages::contains) })
    }

    fun saveDraft(asDraft: Boolean = false) = action {
        val source = _checkpoint.value.draft ?: error("Create a draft first.")
        if (!asDraft && source.enabled) container.protection.requireReady()
        val now = container.clock()
        val rule = withContext(Dispatchers.Default) {
            val candidate = ruleFrom(source, now, !asDraft && source.enabled)
            draftError(source.copy(enabled = candidate.enabled), candidate, _state.value.rules, now)?.let { error(it) }
            candidate
        }
        check(withContext(Dispatchers.IO) { rule.packages.none { it in AppCatalog(getApplication<Application>()).protectedPackages } }) { "Keep essential system controls available." }
        editCheckpoint { it.copy(saving = true) }
        flushDraft()
        val saved = try { repository.save(rule, container.clock()) }
        catch (failure: Exception) { editCheckpoint { it.copy(saving = false) }; throw failure }
        val cleared = editCheckpoint { it.copy(draft = null, settingsReturn = null, saving = false, hasEdits = false) }
        try { container.draftStore.write(cleared) } catch (failure: Exception) { if (failure is CancellationException) throw failure }
        val configured = try { reschedule(); true } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            false
        }
        var acknowledged = true
        if (saved && rule.enabled && configured) {
            val current = repository.snapshot().rules.first { it.id == rule.id }
            val key = "${current.id}:${current.revision}"
            acknowledged = withTimeoutOrNull(3000) {
                container.runtime.first { key in it.configuredKeys || !it.connected || it.error != null }
            }?.let { it.connected && it.error == null && key in it.configuredKeys } == true
        }
        channel.send(UiEvent.Saved(when {
            !saved -> "Review the waiting period to confirm this change."
            asDraft -> "Routine saved. Turn it on when you are ready."
            !acknowledged || !configured -> "Saved. Review app protection to confirm blocking."
            rule.kind == RuleKind.QUICK_BLOCK -> "Session locked until its confirmed end."
            rule.enabled -> "Routine saved. Its commitment is locked."
            else -> "Routine changes saved. It is still off."
        }, if (!saved) rule.id else null))
    }

    fun setEnabled(rule: Rule, enabled: Boolean) = action {
        if (enabled) container.protection.requireReady()
        val saved = repository.setEnabled(rule.id, enabled, container.clock())
        reschedule()
        channel.send(UiEvent.Message(if (saved) if (enabled) "Protection turned on." else "Selected protection ended. Other routines still apply." else "A change has been requested. Confirm it after the wait."))
    }

    fun confirmChange(id: String) = action { repository.confirmChange(id, container.clock()); reschedule(); channel.send(UiEvent.Message("Your change was applied.")) }
    fun cancelChange(id: String) = action { repository.cancelChange(id) }
    fun deleteData() = action {
        repository.deleteAll(container.clock())
        val cleared = editCheckpoint { it.copy(draft = null, settingsReturn = null, saving = false, hasEdits = false) }
        container.draftStore.write(cleared)
        reschedule(); channel.send(UiEvent.Saved("Local plans and history deleted."))
    }
    fun acceptManagedProtection() = action {
        withContext(Dispatchers.IO) {
            container.preferences.commitOrThrow("Your setup choice could not be saved.") { putBoolean("managed_disclosure", true) }
        }
        refresh()
        container.managed.reconcile()
    }
    fun acceptStandardProtection(fromDraft: Boolean, onAccepted: () -> Unit) = action {
        checkpointSettings(fromDraft, DraftScreen.BUILDER)
        withContext(Dispatchers.IO) {
            container.preferences.commitOrThrow("Your setup choice could not be saved.") { putBoolean("standard_disclosure", true) }
        }
        refresh()
        container.refreshSignals.tryEmit(Unit)
        onAccepted()
    }
    fun removeManagement() = action {
        container.managed.removeManagement()
        refresh()
        channel.send(UiEvent.Message("Managed setup removed. Your saved routines are off."))
    }

    private suspend fun reschedule() {
        container.protection.reconcile()
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                channel.send(UiEvent.Message(error.message ?: "The change could not be saved. Try again."))
            }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

fun LifeContext.displayName() = when (this) {
    LifeContext.STUDY -> "Study"; LifeContext.WORK -> "Work"; LifeContext.BEDTIME -> "Bedtime"; LifeContext.PERSONAL -> "Personal"
}
