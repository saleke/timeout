package com.timeout.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PolicyOverviewTest {
    private val clock = ClockSnapshot(Instant.parse("2026-11-23T10:00:00Z").toEpochMilli(), 100_000, 4)
    private fun session(id: String, apps: List<String>, minutes: Int) = Rule(id, id, packages = apps, kind = RuleKind.QUICK_BLOCK,
        startedAt = clock.wallTime, endsAt = clock.wallTime + minutes * 60_000,
        elapsedEndsAt = clock.elapsedTime + minutes * 60_000, bootCount = clock.bootCount)

    @Test fun `separate sessions preserve each app identity and different access deadline`() {
        val short = session("study", listOf("video"), 15)
        val long = session("reading", listOf("social"), 45)
        val result = PolicyOverview.evaluate(listOf(long, short), clock)
        assertEquals(setOf("social", "video"), result.decisions.keys)
        assertEquals(short.endsAt, result.decisions.getValue("video").nextAccessAt)
        assertEquals(long.endsAt, result.decisions.getValue("social").nextAccessAt)
        assertEquals(listOf("study"), result.applicableRuleIds["video"])
    }

    @Test fun `a shared session gives every named app the same correctly scoped access`() {
        val shared = session("shared", listOf("social", "video", "games"), 25)
        val result = PolicyOverview.evaluate(listOf(shared), clock)
        assertEquals(3, result.decisions.size)
        assertTrue(result.decisions.values.all { it.nextAccessAt == shared.endsAt })
        assertEquals(setOf("shared"), result.activeRuleIds)
    }

    @Test fun `overlap counts one app and shows compound access rather than the first timer`() {
        val rules = listOf(session("short", listOf("social"), 15), session("long", listOf("social"), 45))
        val result = PolicyOverview.evaluate(rules, clock)
        assertEquals(1, result.decisions.size)
        assertEquals(2, result.decisions.getValue("social").restrictions.size)
        assertEquals(rules.last().endsAt, result.decisions.getValue("social").nextAccessAt)
    }

    @Test fun `future constraints are included in one apps forecast without affecting another`() {
        val quick = session("study", listOf("social", "video"), 120)
        val later = Rule("work", "Work", packages = listOf("social"), kind = RuleKind.BLOCK_DURING,
            days = setOf(1), windows = listOf(TimeWindow(11 * 60, 17 * 60)))
        val result = PolicyOverview.evaluate(listOf(quick, later), clock)
        assertEquals(Instant.parse("2026-11-23T17:00:00Z").toEpochMilli(), result.decisions.getValue("social").nextAccessAt)
        assertEquals(quick.endsAt, result.decisions.getValue("video").nextAccessAt)
        assertEquals(2, result.applicableRuleIds.getValue("social").size)
        assertEquals(setOf("study"), result.activeRuleIds)
    }

    @Test fun `expiry removes only the finished denial and disabled rules add no app rows`() {
        val short = session("short", listOf("social"), 15)
        val long = session("long", listOf("social"), 45)
        val result = PolicyOverview.evaluate(listOf(short, long, session("off", listOf("games"), 60).copy(enabled = false)), clock.at(short.endsAt))
        assertEquals(setOf("long"), result.activeRuleIds)
        assertEquals(listOf("long"), result.decisions.getValue("social").restrictions.map { it.rule.id })
        assertFalse("games" in result.decisions)
    }

    @Test fun `routine commitment is distinct from currently restricted app access`() {
        val routine = Rule("evening", "Evening", packages = listOf("social"), kind = RuleKind.BLOCK_DURING,
            windows = listOf(TimeWindow(19 * 60, 21 * 60)), commitmentEndsAt = clock.wallTime + 7 * 86_400_000L)
        val result = PolicyOverview.evaluate(listOf(routine), clock)
        assertTrue(result.activeRuleIds.isEmpty())
        assertEquals(setOf("evening"), result.lockedRuleIds)
        assertFalse(result.decisions.getValue("social").restricted)
    }

    @Test fun `projection matches direct policy evaluation for every selected app`() {
        val rules = listOf(session("a", listOf("social", "video"), 30), session("b", listOf("social", "games"), 60))
        val result = PolicyOverview.evaluate(rules, clock)
        result.decisions.forEach { (app, decision) -> assertEquals(RuleEngine.decision(app, rules, clock), decision) }
    }

    @Test fun `displayed deadline stays monotonic during a wall clock change and finite after reboot`() {
        val end = DisplayDeadline.at(clock.wallTime + 60_000, clock)
        assertEquals(30_000, end.remaining(clock.copy(wallTime = clock.wallTime + 86_400_000, elapsedTime = clock.elapsedTime + 30_000)))
        assertEquals(0, end.remaining(clock.at(clock.wallTime + 60_000)))
        assertEquals(0, end.remaining(clock.copy(wallTime = clock.wallTime + 60_000, elapsedTime = 10, bootCount = 5)))
    }
}
