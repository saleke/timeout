package com.timeout.app.enforcement

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
import com.timeout.app.AppContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Normal APK activation is the default. Existing provisioned protection retains its authority. */
class ProtectionCoordinator(private val container: AppContainer) {
    private val started = AtomicBoolean()

    fun usesManaged(): Boolean = container.managed.isDeviceOwner() && container.preferences.getBoolean("managed_disclosure", false)
    fun disclosureAccepted(): Boolean = container.preferences.getBoolean(if (usesManaged()) "managed_disclosure" else "standard_disclosure", false)

    fun accessibilityEnabled(): Boolean {
        val context = container.application
        val expected = ComponentName(context, AppBlockingService::class.java)
        return context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == expected }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        container.managed.start() // Retain expiry/release for already owned native resources.
        container.scope.launch {
            combine(container.standardRuntime, container.managedRuntime) { standard, managed -> if (usesManaged()) managed else standard }
                .collect { container.runtime.value = it }
        }
    }

    suspend fun reconcile() {
        container.repository.completeExpired(container.clock())
        if (usesManaged()) container.managed.reconcile()
        else BoundaryScheduler.schedule(container.application, container.repository.snapshot().rules, container.clock())
        container.refreshSignals.tryEmit(Unit)
    }

    suspend fun requireReady() {
        if (usesManaged()) { container.managed.requireReady(); return }
        check(disclosureAccepted()) { "Review app protection setup first." }
        check(accessibilityEnabled()) { "Turn on Time Out in Android Accessibility settings." }
        val current = container.standardRuntime.value
        check(current.connected && current.configurationLoaded && current.error == null) {
            current.error ?: "App protection is connecting. If it does not connect, reopen Android Accessibility settings."
        }
    }
}
