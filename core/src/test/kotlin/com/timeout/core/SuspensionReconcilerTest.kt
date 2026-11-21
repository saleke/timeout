package com.timeout.core

import org.junit.Assert.*
import org.junit.Test

class SuspensionReconcilerTest {
    private class Journal(var leases: Map<String, Long> = emptyMap()) : SuspensionJournal {
        var writable = true
        override fun read() = leases
        override fun write(leases: Map<String, Long>) { check(writable); this.leases = leases }
    }
    private class Native(val journal: Journal) : PackageSuspensionAccess {
        val suspended = mutableSetOf<String>()
        var refuse = emptySet<String>()
        var acknowledgementsOnly = false
        var calls = 0
        override fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String> {
            calls++
            if (suspended) check(journal.leases.keys.containsAll(packages)) { "Ownership must exist before suspension." }
            if (!acknowledgementsOnly) (packages - refuse).forEach { if (suspended) this.suspended.add(it) else this.suspended.remove(it) }
            return packages.intersect(refuse)
        }
        override fun isSuspended(packageName: String) = packageName in suspended
    }

    @Test fun `expiry releases only packages no longer denied by any rule`() {
        val journal = Journal()
        val native = Native(journal)
        val coordinator = SuspensionReconciler(native, journal)
        coordinator.reconcile(mapOf("social" to 2000, "games" to 1000))
        coordinator.reconcile(mapOf("social" to 2000))
        assertEquals(setOf("social"), native.suspended)
        assertEquals(mapOf("social" to 2000L), journal.leases)
        coordinator.reconcile(emptyMap())
        assertTrue(native.suspended.isEmpty())
        assertTrue(journal.leases.isEmpty())
    }

    @Test fun `process restart releases a write-ahead lease even without a native acknowledgement`() {
        val journal = Journal(mapOf("orphan" to 1000))
        val native = Native(journal).apply { suspended.add("orphan"); suspended.add("outside-timeout") }
        SuspensionReconciler(native, journal).reconcile(emptyMap())
        assertEquals(setOf("outside-timeout"), native.suspended)
        assertTrue(journal.leases.isEmpty())
    }

    @Test fun `failed release stays owned and is retried after restart`() {
        val journal = Journal(mapOf("social" to 1000))
        val native = Native(journal).apply { suspended.add("social"); refuse = setOf("social") }
        val result = SuspensionReconciler(native, journal).reconcile(emptyMap())
        assertEquals(setOf("social"), result.unreleased)
        assertTrue("social" in journal.leases)
        native.refuse = emptySet()
        SuspensionReconciler(native, journal).reconcile(emptyMap())
        assertTrue(journal.leases.isEmpty())
        assertTrue(native.suspended.isEmpty())
    }

    @Test fun `partial native failures are not reported as configured`() {
        val journal = Journal()
        val native = Native(journal).apply { refuse = setOf("essential") }
        val result = SuspensionReconciler(native, journal).reconcile(mapOf("social" to 1000, "essential" to 1000))
        assertEquals(setOf("social"), result.confirmed)
        assertEquals(setOf("essential"), result.failed)
    }

    @Test fun `successful return without actual native state is not proof of suspension`() {
        val journal = Journal()
        val native = Native(journal).apply { acknowledgementsOnly = true }
        val result = SuspensionReconciler(native, journal).reconcile(mapOf("social" to 1000))
        assertTrue(result.confirmed.isEmpty())
        assertEquals(setOf("social"), result.failed)
    }

    @Test fun `journal failure prevents untracked native changes`() {
        val journal = Journal().apply { writable = false }
        val native = Native(journal)
        assertThrows(IllegalStateException::class.java) {
            SuspensionReconciler(native, journal).reconcile(mapOf("social" to 1000))
        }
        assertEquals(0, native.calls)
    }
}
