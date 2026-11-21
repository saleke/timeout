package com.timeout.app.enforcement

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.timeout.app.MainActivity
import com.timeout.app.TimeOutApplication
import com.timeout.core.*
import com.timeout.app.data.AppCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BlockingState(val packageName: String, val decision: AccessDecision, val clock: ClockSnapshot)

/** User-enabled, metadata-only observer. It interrupts app opening; it does not suspend packages. */
class AppBlockingService : AccessibilityService() {
    private val container get() = (application as TimeOutApplication).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    @Volatile private var foreground: String? = null
    @Volatile private var nextBoundary: Long? = null
    private var keyboards = emptySet<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        keyboards = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        container.standardRuntime.update { it.copy(connected = true, serviceConnected = true, error = null) }
        scope.launch { requests.collect { reconcile() } }
        scope.launch { container.refreshSignals.collect { requests.tryEmit(Unit) } }
        scope.launch {
            container.repository.state.catch { fail("Saved plans could not be loaded.") }.collect { requests.tryEmit(Unit) }
        }
        scope.launch {
            while (isActive) {
                reconcile()
                delay(nextBoundary?.minus(container.clock().wallTime)?.coerceIn(100, 60_000) ?: 60_000)
            }
        }
        requests.tryEmit(Unit)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val name = event?.packageName?.toString() ?: return
        if (name in keyboards || name == "com.android.systemui") return
        if (name == packageName) {
            if (event.className?.toString() == MainActivity::class.java.name) {
                foreground = null
                requests.tryEmit(Unit)
            }
            return
        }
        foreground = name
        requests.tryEmit(Unit)
    }

    private suspend fun reconcile() = mutex.withLock {
        try {
            val now = container.clock()
            container.repository.completeExpired(now)
            val snapshot = container.repository.snapshot()
            check(snapshot.unreadable == 0) { "Some saved plans need repair." }
            nextBoundary = RuleEngine.nextBoundary(snapshot.rules, now)
            if (!container.protection.usesManaged()) BoundaryScheduler.schedule(this, snapshot.rules, now)
            val accepted = container.preferences.getBoolean("standard_disclosure", false)
            container.standardRuntime.update { it.copy(configurationLoaded = accepted, checkedAt = now.wallTime,
                configuredKeys = if (accepted) snapshot.rules.filter { rule -> rule.enabled }.map { rule -> "${rule.id}:${rule.revision}" }.toSet() else emptySet(),
                error = if (accepted) null else "Review app protection setup.") }
            val target = foreground
            val excluded = withContext(Dispatchers.IO) { AppCatalog(this@AppBlockingService).protectedPackages }
            if (!accepted || container.protection.usesManaged() || target == null || target in excluded) {
                clearBlock(); return@withLock
            }
            val decision = RuleEngine.decision(target, snapshot.rules, now)
            if (!decision.restricted) { clearBlock(); return@withLock }
            if (target != foreground) return@withLock
            container.blockingState.value = BlockingState(target, decision, now)
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked || container.standardRuntime.value.blockedPackage == target) return@withLock
            withContext(Dispatchers.Main.immediate) {
                if (target == foreground) startActivity(Intent(this@AppBlockingService, BlockingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("target_package", target))
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            fail(failure.message ?: "Re-enable app protection in Android settings.")
        }
    }

    private fun clearBlock() {
        container.blockingState.value = null
        container.standardRuntime.update { it.copy(blockedPackage = null) }
    }
    private fun fail(message: String) {
        clearBlock()
        container.standardRuntime.update { it.copy(configurationLoaded = false, configuredKeys = emptySet(), error = message) }
    }
    override fun onInterrupt() { requests.tryEmit(Unit) }
    override fun onDestroy() {
        clearBlock()
        scope.cancel()
        container.standardRuntime.update { it.copy(connected = false, serviceConnected = false, configurationLoaded = false, configuredKeys = emptySet()) }
        super.onDestroy()
    }
}
