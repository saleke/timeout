package com.timeout.core

/** Android implements these ports; policy and crash ordering can be tested on the host JVM. */
interface PackageSuspensionAccess {
    /** Return the packages Android did not change as requested. */
    fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String>
    fun isSuspended(packageName: String): Boolean
}

interface SuspensionJournal {
    /** Package -> finite planned release instant. This is a write-ahead ownership journal. */
    fun read(): Map<String, Long>
    fun write(leases: Map<String, Long>)
}

data class SuspensionResult(val confirmed: Set<String>, val failed: Set<String>, val unreleased: Set<String>)

class SuspensionReconciler(private val access: PackageSuspensionAccess, private val journal: SuspensionJournal) {
    fun reconcile(desired: Map<String, Long>): SuspensionResult {
        require(desired.values.all { it > 0 }) { "Every suspension needs a finite release." }
        val previous = journal.read()
        // Persist intent before either native call: death between native success and acknowledgement
        // must never leave an untracked suspension. Retrying native operations is idempotent.
        val owned = previous + desired
        journal.write(owned)
        val release = previous.keys - desired.keys
        val unreleased = apply(release, false)
        val failed = apply(desired.keys, true).toMutableSet()
        val confirmed = desired.keys.filterTo(mutableSetOf()) { name ->
            name !in failed && runCatching { access.isSuspended(name) }.getOrDefault(false)
        }
        failed.addAll(desired.keys - confirmed)
        journal.write(owned.filterKeys { it in desired || it in unreleased })
        return SuspensionResult(confirmed, failed, unreleased)
    }

    private fun apply(packages: Set<String>, suspended: Boolean): Set<String> =
        if (packages.isEmpty()) emptySet()
        else runCatching { access.setSuspended(packages, suspended).intersect(packages) }.getOrDefault(packages)
}
