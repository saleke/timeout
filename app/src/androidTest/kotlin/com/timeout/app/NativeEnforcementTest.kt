package com.timeout.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.timeout.app.data.AppCatalog
import com.timeout.app.enforcement.BoundaryScheduler
import com.timeout.app.enforcement.TimeOutDeviceAdminReceiver
import com.timeout.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit opt-in, provisioned dedicated device, empty database. Never enrolls or resets a device. */
@RunWith(AndroidJUnit4::class)
class NativeEnforcementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as TimeOutApplication
    private val container get() = application.container
    private val dpm get() = application.getSystemService(DevicePolicyManager::class.java)
    private val admin get() = ComponentName(application, TimeOutDeviceAdminReceiver::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var initialized = false
    private var latestEnd = 0L

    @Before fun setup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("managedDeviceTests") == "true")
        assumeTrue(container.managed.isDeviceOwner() && BoundaryScheduler.exactAccess(application))
        assumeTrue(container.repository.snapshot().rules.isEmpty())
        assumeTrue(container.preferences.getBoolean("managed_disclosure", false))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        initialized = true
    }

    @After fun cleanup() = runBlocking {
        if (!initialized) return@runBlocking
        // Even tests wait for their commitments; cleanup has no privileged early-stop hook.
        val wait = (latestEnd - SystemClock.elapsedRealtime()).coerceIn(0, 15_000)
        if (wait > 0) Thread.sleep(wait + 100)
        container.repository.completeExpired(container.clock())
        container.repository.deleteAll(container.clock())
        container.managed.reconcile()
        scenario?.close()
    }

    private fun targetPackage(): String {
        val apps = AppCatalog(application).load().filter { !dpm.isPackageSuspended(admin, it.packageName) }
        assumeTrue(apps.isNotEmpty())
        return apps.firstOrNull { it.packageName.contains("calculator") }?.packageName ?: apps.first().packageName
    }

    private fun session(target: String, milliseconds: Long): Rule {
        val now = container.clock()
        latestEnd = maxOf(latestEnd, now.elapsedTime + milliseconds)
        return Rule(UUID.randomUUID().toString(), "Native suspension test", LifeContext.STUDY, listOf(target), RuleKind.QUICK_BLOCK,
            startedAt = now.wallTime, endsAt = now.wallTime + milliseconds,
            elapsedEndsAt = now.elapsedTime + milliseconds, bootCount = now.bootCount, createdAt = now.wallTime)
    }

    @Test fun suspensionExpiresWithTheMainActivityClosed() = runBlocking {
        val target = targetPackage()
        container.repository.save(session(target, 4000), container.clock())
        container.managed.reconcile()
        assertTrue(dpm.isPackageSuspended(admin, target))
        scenario?.close()
        scenario = null
        waitFor("native expiry release") { !dpm.isPackageSuspended(admin, target) }
        assertFalse(container.repository.snapshot().rules.single().enabled)
    }

    @Test fun anIndependentCommitmentSurvivesTheFirstExpiryAndRejectsRecovery() = runBlocking {
        val target = targetPackage()
        val first = session(target, 2000)
        val second = session(target, 6000)
        container.repository.save(first, container.clock())
        container.repository.save(second, container.clock())
        container.managed.reconcile()
        assertTrue(dpm.isPackageSuspended(admin, target))
        waitFor("first timer completion") { "${first.id}:1" !in container.runtime.value.configuredKeys }
        assertTrue(dpm.isPackageSuspended(admin, target))
        try { container.repository.recover(null, container.clock()); fail("Recovery bypassed a commitment") }
        catch (_: IllegalStateException) { /* expected */ }
        assertTrue(dpm.isPackageSuspended(admin, target))
        waitFor("last timer release") { !dpm.isPackageSuspended(admin, target) }
    }

    private fun waitFor(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $label. Runtime: ${container.runtime.value}")
    }
}
