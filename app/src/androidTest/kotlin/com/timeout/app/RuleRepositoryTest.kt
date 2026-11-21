package com.timeout.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.timeout.app.data.*
import com.timeout.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleRepositoryTest {
    private lateinit var database: TimeOutDatabase
    private lateinit var repository: RuleRepository
    private val clock = ClockSnapshot(1_800_000_000_000, 100_000, 3)
    private fun session(id: String, duration: Long = 600_000) = Rule(id, id,
        packages = listOf("example.distraction"), kind = RuleKind.QUICK_BLOCK,
        startedAt = clock.wallTime, endsAt = clock.wallTime + duration,
        elapsedEndsAt = clock.elapsedTime + duration, bootCount = clock.bootCount)

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TimeOutDatabase::class.java).build()
        repository = RuleRepository(database)
    }
    @After fun closeDatabase() = database.close()

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("A locked mutation was accepted") } catch (_: IllegalStateException) { /* expected */ }
    }

    @Test fun everyMutationPathPreservesLockedSessions() = runBlocking {
        repository.save(session("study"), clock)
        rejected { repository.setEnabled("study", false, clock) }
        rejected { repository.save(session("study").copy(packages = listOf("different.app")), clock) }
        rejected { repository.recover("study", clock) }
        rejected { repository.recover(null, clock) }
        rejected { repository.deleteAll(clock) }
        assertTrue(repository.snapshot().rules.single().enabled)
        assertEquals(Commitment.LOCKED, RuleRepository(database).snapshot().rules.single().commitment)
    }

    @Test fun routineCannotBeWeakenedBetweenItsOccurrences() = runBlocking {
        val routine = session("routine").copy(kind = RuleKind.BLOCK_DURING, days = setOf(1), windows = listOf(TimeWindow(540, 600)),
            commitmentEndsAt = clock.wallTime + 7 * 86_400_000L, commitmentElapsedEndsAt = clock.elapsedTime + 7 * 86_400_000L)
        repository.save(routine, clock)
        assertFalse(RuleEngine.restricts(routine, clock))
        rejected { repository.setEnabled(routine.id, false, clock) }
        rejected { repository.save(routine.copy(commitmentEndsAt = clock.wallTime, commitmentElapsedEndsAt = clock.elapsedTime), clock) }
        val later = clock.at(routine.commitmentEndsAt)
        assertTrue(repository.setEnabled(routine.id, false, later))
    }

    @Test fun stalePendingChangeCannotUnlockANewCommitment() = runBlocking {
        repository.save(session("locked"), clock)
        database.dao().putChange(PendingChange("locked", 1, null, clock.wallTime, clock.elapsedTime, clock.bootCount, clock.wallTime + 360_000))
        rejected { repository.confirmChange("locked", clock) }
        assertTrue(repository.snapshot().rules.single().enabled)
    }

    @Test fun expiryOfOneSessionPreservesTheIndependentRestriction() = runBlocking {
        repository.save(session("short", 60_000), clock)
        repository.save(session("long"), clock)
        val later = clock.at(clock.wallTime + 60_000)
        repository.completeExpired(later)
        assertFalse(repository.snapshot().rules.first { it.id == "short" }.enabled)
        assertTrue(RuleEngine.decision("example.distraction", repository.snapshot().rules, later).restricted)
    }

    @Test fun repeatedExpiryDoesNotCreateDuplicateHistory() = runBlocking {
        repository.save(session("short"), clock)
        val later = clock.at(clock.wallTime + 600_001)
        repository.completeExpired(later)
        repository.completeExpired(later)
        assertEquals(1, repository.history.first().count { it.title == "Session finished" })
        repository.deleteAll(later)
        assertTrue(repository.snapshot().rules.isEmpty())
    }

    @Test fun anOldOmittedCommitmentDoesNotBecomeALockedAgreementOnUpgrade() = runBlocking {
        // Old serialization omitted its FLEXIBLE default. New records explicitly encode defaults.
        val payload = """{"id":"old","name":"Old session","packages":["example.distraction"],"kind":"QUICK_BLOCK","startedAt":${clock.wallTime},"endsAt":${clock.wallTime + 60_000}}"""
        database.dao().putRule(RuleRecord("old", payload, true, clock.wallTime))
        val decoded = repository.snapshot().rules.single()
        assertEquals(Commitment.FLEXIBLE, decoded.commitment)
        assertTrue(repository.setEnabled("old", false, clock))
        rejected { repository.setEnabled("old", true, clock) }
    }
}
