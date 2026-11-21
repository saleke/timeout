package com.timeout.app.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.content.pm.PackageManager
import com.timeout.app.BuildConfig
import com.timeout.app.AppContainer
import com.timeout.app.data.AppCatalog
import com.timeout.app.data.commitOrThrow
import androidx.core.content.edit
import com.timeout.core.CommitmentPolicy
import com.timeout.core.ClockSnapshot
import com.timeout.core.PackageSuspensionAccess
import com.timeout.core.RuleEngine
import com.timeout.core.SuspensionJournal
import com.timeout.core.SuspensionReconciler
import com.timeout.core.SuspensionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/** Reconciles durable intent with Android package suspension, never with visual coverage. */
class ManagedProtectionController(private val container: AppContainer) {
    private val context = container.application
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, TimeOutDeviceAdminReceiver::class.java)
    private val journalPreferences = context.getSharedPreferences("managed_suspensions", 0)
    private val started = AtomicBoolean()
    private val mutex = Mutex()
    private var nextCheckAt: Long? = null
    private val journal = object : SuspensionJournal {
        override fun read(): Map<String, Long> = Json.decodeFromString(journalPreferences.getString("leases", "{}") ?: "{}")
        override fun write(leases: Map<String, Long>) {
            journalPreferences.commitOrThrow("The native ownership journal could not be saved.") {
                putString("leases", Json.encodeToString(leases))
            }
        }
    }
    private val access = object : PackageSuspensionAccess {
        override fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String> {
            val installed = if (suspended) packages else packages.filterTo(mutableSetOf()) { name ->
                try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(name, PackageManager.MATCH_ALL)
                    true
                } catch (_: PackageManager.NameNotFoundException) { false }
            }
            return if (installed.isEmpty()) emptySet() else dpm.setPackagesSuspended(admin, installed.toTypedArray(), suspended).toSet()
        }
        override fun isSuspended(packageName: String): Boolean = dpm.isPackageSuspended(admin, packageName)
    }
    private val reconciler = SuspensionReconciler(access, journal)

    fun isDeviceOwner(): Boolean = runCatching { dpm?.isDeviceOwnerApp(context.packageName) == true }.getOrDefault(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        container.scope.launch {
            container.repository.state.catch { failed("Saved rules could not be observed. Their records are preserved.") }
                .collect { reconcile() }
        }
        container.scope.launch { container.refreshSignals.collect { reconcile() } }
        container.scope.launch {
            while (isActive) {
                reconcile()
                val wait = nextCheckAt?.minus(container.clock().wallTime)?.coerceIn(100, 60_000) ?: 300_000
                delay(wait)
            }
        }
    }

    suspend fun requireReady() {
        reconcile()
        check(isDeviceOwner()) { "Managed-device setup is required. Installing the APK does not grant device-owner access." }
        check(container.preferences.getBoolean("managed_disclosure", false)) { "Review and accept managed app protection first." }
        check(BoundaryScheduler.exactAccess(context)) { "Allow Alarms & reminders so Android can apply your start and release times." }
        check(container.managedRuntime.value.configurationLoaded && container.managedRuntime.value.error == null) {
            container.managedRuntime.value.error ?: "Native protection has not been verified."
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = container.clock()
            val owner = isDeviceOwner()
            container.managedRuntime.update { it.copy(connected = owner, exactAlarms = BoundaryScheduler.exactAccess(context), checkedAt = now.wallTime) }
            if (!owner) {
                container.managedRuntime.update { it.copy(configurationLoaded = false, configuredKeys = emptySet(), suspendedPackages = emptySet(),
                    error = "Managed-device setup is required for OS-level app suspension.") }
                return@withLock
            }
            try {
                container.repository.completeExpired(now)
                val snapshot = container.repository.snapshot()
                check(snapshot.unreadable == 0) { "Some protection records could not be read. Native leases keep their last finite release times." }
                val rules = snapshot.rules
                val guardUntil = rules.filter { CommitmentPolicy.isLocked(it, now) }.maxOfOrNull { CommitmentPolicy.endsAt(it, now) }
                if (!container.preferences.getBoolean("managed_disclosure", false)) {
                    // Legacy overlay plans must not acquire stronger native authority before
                    // the person accepts managed protection. Existing owned leases still expire.
                    updateSelfGuard(guardUntil)
                    honorSavedLeases(now)
                    failed("Review and accept managed app protection before applying saved plans.")
                    return@withLock
                }
                val excluded = AppCatalog(context).protectedPackages
                val desired = rules.filter { it.enabled }.flatMap { it.packages }.distinct()
                    .filter { it !in excluded }.mapNotNull { name ->
                        val decision = RuleEngine.decision(name, rules, now)
                        if (!decision.restricted) null else name to (decision.nextAccessAt
                            ?: error("Overlapping rules have no established release time. Review the saved configuration."))
                    }.toMap()
                val ownedCheckpoint = journal.read().values.minOrNull()?.coerceAtLeast(now.wallTime + 60_000)
                nextCheckAt = listOfNotNull(RuleEngine.nextBoundary(rules, now), ownedCheckpoint).minOrNull()
                // Schedule before suspension, so a process death after the native call still has
                // an independent release callback. The owner-bound service is a second path.
                val scheduled = BoundaryScheduler.scheduleAt(context, nextCheckAt, now)
                updateSelfGuard(guardUntil)
                val result = reconciler.reconcile(desired)
                if (result.failed.isNotEmpty() || result.unreleased.isNotEmpty()) {
                    nextCheckAt = minOf(nextCheckAt ?: Long.MAX_VALUE, now.wallTime + 60_000)
                    BoundaryScheduler.scheduleAt(context, nextCheckAt, now)
                }
                val rejectedEssentials = rules.filter { it.enabled }.flatMap { it.packages }.toSet().intersect(excluded)
                val error = when {
                    result.unreleased.isNotEmpty() -> "Android could not release: ${result.unreleased.joinToString()}. Release will be retried."
                    result.failed.isNotEmpty() -> "Android did not confirm suspension for: ${result.failed.joinToString()}."
                    rejectedEssentials.isNotEmpty() -> "A saved selection contains essential system controls. These apps are never suspended."
                    !scheduled -> "Alarms & reminders access is needed for reliable boundaries. Review setup."
                    else -> null
                }
                container.managedRuntime.update { it.copy(configurationLoaded = true, checkedAt = now.wallTime,
                    suspendedPackages = result.confirmed, releasePending = result.unreleased, error = error,
                    configuredKeys = if (error == null) rules.filter { r -> r.enabled }.map { r -> "${r.id}:${r.revision}" }.toSet() else emptySet()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed(error.message ?: "Native protection needs attention.")
                // If policy storage becomes unreadable, honor only the already persisted finite
                // leases. Do not invent a new end or leave a package orphaned indefinitely.
                runCatching { honorSavedLeases(now) }
            }
        }
    }

    private fun failed(message: String) {
        container.managedRuntime.update { it.copy(configurationLoaded = false, configuredKeys = emptySet(), error = message) }
    }

    private fun honorSavedLeases(now: ClockSnapshot): SuspensionResult {
        val excluded = AppCatalog(context).protectedPackages
        val leases = journal.read()
        val remaining = leases.filter { (name, end) -> end > now.wallTime && name !in excluded }
        val guardUntil = journalPreferences.getLong("self_guard_until", 0)
        val guardRelease = runCatching { if (guardUntil <= now.wallTime) updateSelfGuard(null) }
        nextCheckAt = (remaining.values + listOfNotNull(guardUntil.takeIf { it > now.wallTime },
            (now.wallTime + 60_000).takeIf { leases.keys != remaining.keys || guardRelease.isFailure })).minOrNull()
        BoundaryScheduler.scheduleAt(context, nextCheckAt, now)
        val result = reconciler.reconcile(remaining)
        if (result.failed.isNotEmpty() || result.unreleased.isNotEmpty() || guardRelease.isFailure) {
            nextCheckAt = minOf(nextCheckAt ?: Long.MAX_VALUE, now.wallTime + 60_000)
            BoundaryScheduler.scheduleAt(context, nextCheckAt, now)
        }
        container.managedRuntime.update { it.copy(suspendedPackages = result.confirmed, releasePending = result.unreleased) }
        // Failure to clear self-protection must never prevent attempting app release first.
        guardRelease.getOrThrow()
        return result
    }

    private fun updateSelfGuard(until: Long?) {
        if (Build.VERSION.SDK_INT < 30) return
        val current = dpm.getUserControlDisabledPackages(admin).toSet()
        val added = journalPreferences.getBoolean("self_guard_added", false)
        if (until != null && (!added || journalPreferences.getLong("self_guard_until", 0) != until)) {
            journalPreferences.commitOrThrow("Native self-protection ownership could not be saved.") {
                putBoolean("self_guard_added", true)
                putLong("self_guard_until", until)
            }
        }
        if (until != null && (!added || context.packageName !in current)) {
            // API 34+ getters expose resolved policies from all admins. Set only our own list;
            // copying that resolved list would adopt another admin's unrelated restrictions.
            dpm.setUserControlDisabledPackages(admin, listOf(context.packageName))
            check(context.packageName in dpm.getUserControlDisabledPackages(admin)) { "Android did not confirm protection against clearing Timeout's data." }
        } else if (until == null && added) {
            dpm.setUserControlDisabledPackages(admin, emptyList())
            journalPreferences.commitOrThrow("Native self-protection release could not be saved.") {
                putBoolean("self_guard_added", false)
                remove("self_guard_until")
            }
        }
    }

    @Suppress("DEPRECATION")
    suspend fun removeManagement() {
        check(BuildConfig.ALLOW_TEST_DEVICE_REMOVAL) { "Removing test-device management is only available in development builds." }
        // The same repository guard applies even if this action is reached through a stale UI.
        container.repository.recover(null, container.clock())
        reconcile()
        mutex.withLock {
            check(journal.read().isEmpty()) { "Android must confirm release of Timeout-owned apps before management can be removed." }
            check(container.repository.snapshot().rules.none { CommitmentPolicy.isLocked(it, container.clock()) }) { "A commitment is still locked." }
            updateSelfGuard(null)
            dpm.clearDeviceOwnerApp(context.packageName)
            check(!isDeviceOwner()) { "Android did not confirm removal of test-device management." }
            container.preferences.edit { putBoolean("managed_disclosure", false) }
        }
        reconcile()
    }
}
