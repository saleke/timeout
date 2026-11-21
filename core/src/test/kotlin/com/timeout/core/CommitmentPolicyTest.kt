package com.timeout.core

import org.junit.Assert.*
import org.junit.Test

class CommitmentPolicyTest {
    private val clock = ClockSnapshot(1_800_000_000_000, 100_000, 7)
    private val session = Rule("session", "Study", packages = listOf("social"), kind = RuleKind.QUICK_BLOCK,
        startedAt = clock.wallTime, endsAt = clock.wallTime + 60_000,
        elapsedEndsAt = clock.elapsedTime + 60_000, bootCount = 7)

    @Test fun `every policy edit is rejected before a session expires`() {
        val changes = listOf(session.copy(enabled = false), session.copy(packages = emptyList()),
            session.copy(commitment = Commitment.FLEXIBLE), session.copy(endsAt = clock.wallTime),
            session.copy(kind = RuleKind.BLOCK_DURING), session.copy(elapsedEndsAt = clock.elapsedTime),
            session.copy(bootCount = -1), session.copy(zoneId = "Pacific/Honolulu"))
        changes.forEach { replacement ->
            assertThrows(IllegalStateException::class.java) { CommitmentPolicy.requireReplacement(session, replacement, clock) }
        }
        CommitmentPolicy.requireReplacement(session, session.copy(name = "A better label", revision = 2), clock)
    }

    @Test fun `session lock survives a wall clock jump and releases at the monotonic boundary`() {
        assertTrue(CommitmentPolicy.isLocked(session, clock.copy(wallTime = clock.wallTime + 86_400_000)))
        assertFalse(CommitmentPolicy.isLocked(session, clock.copy(elapsedTime = clock.elapsedTime + 60_000)))
        CommitmentPolicy.requireEditable(session, clock.at(session.endsAt))
    }

    @Test fun `routine commitment remains locked outside protected occurrences`() {
        val routine = session.copy(kind = RuleKind.BLOCK_DURING, days = setOf(1), windows = listOf(TimeWindow(540, 600)),
            commitmentEndsAt = clock.wallTime + 7 * 86_400_000L,
            commitmentElapsedEndsAt = clock.elapsedTime + 7 * 86_400_000L)
        assertFalse(RuleEngine.restricts(routine, clock))
        assertTrue(CommitmentPolicy.isLocked(routine, clock))
        assertThrows(IllegalStateException::class.java) { CommitmentPolicy.requireEditable(routine, clock) }
    }

    @Test fun `routine unlock changes editability without disabling its schedule`() {
        val routine = session.copy(kind = RuleKind.BLOCK_DURING, days = (1..7).toSet(), windows = listOf(TimeWindow(0, 1439)),
            commitmentEndsAt = clock.wallTime + 1000, commitmentElapsedEndsAt = clock.elapsedTime + 1000)
        val later = clock.at(clock.wallTime + 1000)
        assertFalse(CommitmentPolicy.isLocked(routine, later))
        assertTrue(RuleEngine.restricts(routine, later))
        CommitmentPolicy.requireEditable(routine, later)
    }

    @Test fun `a different boot uses the finite persisted commitment deadline`() {
        val routine = session.copy(kind = RuleKind.BLOCK_DURING, commitmentEndsAt = clock.wallTime + 1000,
            commitmentElapsedEndsAt = clock.elapsedTime + 1000)
        assertFalse(CommitmentPolicy.isLocked(routine, clock.copy(wallTime = clock.wallTime + 1000, elapsedTime = 10, bootCount = 8)))
    }

    @Test fun `activation requires a reviewed locked future commitment`() {
        assertNull(CommitmentPolicy.activationError(session, clock))
        assertNotNull(CommitmentPolicy.activationError(session.copy(commitment = Commitment.FLEXIBLE), clock))
        assertNotNull(CommitmentPolicy.activationError(session.copy(kind = RuleKind.BLOCK_DURING), clock))
        assertNotNull(CommitmentPolicy.activationError(session, clock.at(session.endsAt)))
        assertNull(CommitmentPolicy.activationError(session.copy(enabled = false), clock.at(session.endsAt)))
    }
}
