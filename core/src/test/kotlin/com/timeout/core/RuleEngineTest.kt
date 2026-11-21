package com.timeout.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class RuleEngineTest {
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun clock(value: String) = ClockSnapshot(time(value))
    private fun plan(
        id: String = "routine", kind: RuleKind = RuleKind.BLOCK_DURING,
        windows: List<TimeWindow> = listOf(TimeWindow(9 * 60, 17 * 60)),
        days: Set<Int> = (1..5).toSet(), zone: String = "UTC",
    ) = Rule(id, "Work", packages = listOf("social"), kind = kind, windows = windows, days = days, zoneId = zone)

    @Test fun `periods include start and exclude end`() {
        val rule = plan()
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T08:59:59Z")))
        assertTrue(RuleEngine.restricts(rule, clock("2026-11-23T09:00:00Z")))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T17:00:00Z")))
    }

    @Test fun `overnight protection belongs to its start day`() {
        val rule = plan(windows = listOf(TimeWindow(22 * 60, 7 * 60, true)), days = setOf(7))
        assertTrue(RuleEngine.restricts(rule, clock("2026-11-23T06:59:00Z")))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T07:00:00Z")))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-24T06:00:00Z")))
    }

    @Test fun `access windows are alternatives within one plan`() {
        val rule = plan(kind = RuleKind.ALLOW_ONLY, windows = listOf(TimeWindow(720, 780), TimeWindow(1140, 1170)))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T12:30:00Z")))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T19:20:00Z")))
        assertTrue(RuleEngine.restricts(rule, clock("2026-11-23T19:30:00Z")))
    }

    @Test fun `unselected access-plan days are unrestricted`() {
        val rule = plan(kind = RuleKind.ALLOW_ONLY, windows = listOf(TimeWindow(720, 780)))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-28T09:00:00Z")))
    }

    @Test fun `overnight allowed window carries into a selected next day`() {
        val rule = plan(kind = RuleKind.ALLOW_ONLY, windows = listOf(TimeWindow(1380, 60, true)), days = setOf(7, 1))
        assertFalse(RuleEngine.restricts(rule, clock("2026-11-23T00:30:00Z")))
        assertTrue(RuleEngine.restricts(rule, clock("2026-11-23T01:00:00Z")))
    }

    @Test fun `quick block ending does not remove a recurring restriction`() {
        val now = clock("2026-11-23T10:00:00Z")
        val quick = Rule("quick", "Study", packages = listOf("social"), kind = RuleKind.QUICK_BLOCK,
            startedAt = now.wallTime - 1, endsAt = now.wallTime + 30 * 60_000)
        val rules = listOf(quick, plan())
        assertEquals(2, RuleEngine.decision("social", rules, now).restrictions.size)
        val afterQuick = now.at(quick.endsAt)
        assertEquals(1, RuleEngine.decision("social", rules, afterQuick).restrictions.size)
        assertEquals(time("2026-11-23T17:00:00Z"), RuleEngine.decision("social", rules, now).nextAccessAt)
    }

    @Test fun `next access includes a future restriction that starts before another releases`() {
        val rules = listOf(plan(id = "morning", windows = listOf(TimeWindow(540, 720))),
            plan(id = "afternoon", windows = listOf(TimeWindow(660, 1020))))
        assertEquals(time("2026-11-23T17:00:00Z"), RuleEngine.decision("social", rules, clock("2026-11-23T10:00:00Z")).nextAccessAt)
    }

    @Test fun `monotonic duration survives wall-clock changes within a boot`() {
        val rule = Rule("q", "Study", packages = listOf("social"), kind = RuleKind.QUICK_BLOCK,
            startedAt = 0, endsAt = 2_000_000, elapsedEndsAt = 100_000, bootCount = 4)
        assertTrue(RuleEngine.restricts(rule, ClockSnapshot(9_000_000, 90_000, 4)))
        assertFalse(RuleEngine.restricts(rule, ClockSnapshot(9_000_000, 100_000, 4)))
    }

    @Test fun `new boot uses persisted finite wall expiry`() {
        val rule = Rule("q", "Study", packages = listOf("social"), kind = RuleKind.QUICK_BLOCK,
            startedAt = 0, endsAt = 5000, elapsedEndsAt = 100_000, bootCount = 4)
        assertFalse(RuleEngine.restricts(rule, ClockSnapshot(6000, 1000, 5)))
    }

    @Test fun `moving the wall clock behind the start does not cancel elapsed protection`() {
        val rule = Rule("q", "Study", packages = listOf("social"), kind = RuleKind.QUICK_BLOCK,
            startedAt = 100_000, endsAt = 160_000, elapsedEndsAt = 120_000, bootCount = 4)
        assertTrue(RuleEngine.restricts(rule, ClockSnapshot(50_000, 100_000, 4)))
    }

    @Test fun `DST gap shifts a nonexistent start forward`() {
        val rule = plan(windows = listOf(TimeWindow(150, 240)), days = setOf(7), zone = "America/New_York")
        assertFalse(RuleEngine.restricts(rule, clock("2026-03-08T07:00:00Z")))
        assertTrue(RuleEngine.restricts(rule, clock("2026-03-08T07:30:00Z")))
        assertFalse(RuleEngine.restricts(rule, clock("2026-03-08T08:00:00Z")))
    }

    @Test fun `other apps and disabled rules do not contribute restrictions`() {
        assertFalse(RuleEngine.decision("essential", listOf(plan()), clock("2026-11-23T10:00:00Z")).restricted)
        assertFalse(RuleEngine.decision("social", listOf(plan().copy(enabled = false)), clock("2026-11-23T10:00:00Z")).restricted)
    }

    @Test fun `invalid windows and empty selections are rejected`() {
        assertNotNull(RuleEngine.validate(plan(windows = listOf(TimeWindow(600, 500)))))
        assertNotNull(RuleEngine.validate(plan().copy(packages = emptyList())))
        assertNull(RuleEngine.validate(plan(windows = listOf(TimeWindow(1320, 420, true)))))
    }

    @Test fun `manual time window duration distinguishes same day and next day`() {
        assertEquals(480, TimeWindow(9 * 60, 17 * 60).durationMinutes())
        assertEquals(510, TimeWindow(22 * 60 + 30, 7 * 60, true).durationMinutes())
        assertEquals(0, TimeWindow(9 * 60, 9 * 60).durationMinutes())
        assertEquals(1440, TimeWindow(9 * 60, 9 * 60, true).durationMinutes())
    }

    @Test fun `disabled routines cannot hide a week with no access opportunity`() {
        val invalid = plan(windows = listOf(TimeWindow(0, 720), TimeWindow(720, 0, true)), days = (1..7).toSet()).copy(enabled = false)
        assertNotNull(RuleEngine.validate(invalid))
        assertNull(RuleEngine.validate(plan(windows = listOf(TimeWindow(1320, 420, true)), days = (1..7).toSet()).copy(enabled = false)))
    }

    @Test fun `incompatible independent plans report no shared access rather than a false unlock time`() {
        val rules = listOf(plan("first", windows = listOf(TimeWindow(0, 720)), days = (1..7).toSet()),
            plan("second", windows = listOf(TimeWindow(720, 0, true)), days = (1..7).toSet()))
        val result = RuleEngine.decision("social", rules, clock("2026-11-23T10:00:00Z"))
        assertTrue(result.restricted)
        assertNull(result.nextAccessAt)
    }
}
