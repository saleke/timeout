package com.timeout.app.ui

import com.timeout.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RuleDraftTest {
    private val zone = ZoneId.systemDefault()
    private val clock = ClockSnapshot(LocalDateTime.of(2026, 11, 21, 10, 0).atZone(zone).toInstant().toEpochMilli(), 100_000, 4)

    @Test fun `new sessions start empty with their own identity and default time`() {
        val first = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.STUDY, clock).copy(packages = setOf("already.blocked"), minutes = 90)
        val next = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.STUDY, clock)
        assertTrue(next.packages.isEmpty())
        assertEquals(45, next.minutes)
        assertNotEquals(first.id, next.id)
        assertFalse(next.untilTime)
    }

    @Test fun `a newly selected app does not extend or replace another apps session`() {
        val existing = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.WORK, clock)
            .copy(packages = setOf("app.old"), minutes = 15).toRule(clock)
        val next = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.STUDY, clock)
            .copy(packages = setOf("app.new"), minutes = 90).toRule(clock)
        val rules = listOf(existing, next)
        assertEquals(clock.wallTime + 15 * 60_000, RuleEngine.decision("app.old", rules, clock).nextAccessAt)
        assertEquals(clock.wallTime + 90 * 60_000, RuleEngine.decision("app.new", rules, clock).nextAccessAt)
        assertEquals(listOf("app.old"), existing.packages)
    }

    @Test fun `time spent granting access does not consume a duration`() {
        val draft = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.STUDY, clock).copy(packages = setOf("app.new"), minutes = 45)
        val afterSetup = clock.at(clock.wallTime + 30 * 60_000)
        val rule = draft.toRule(afterSetup)
        assertEquals(afterSetup.wallTime, rule.startedAt)
        assertEquals(afterSetup.wallTime + 45 * 60_000, rule.endsAt)
        assertEquals(afterSetup.elapsedTime + 45 * 60_000, rule.elapsedEndsAt)
    }

    @Test fun `a chosen Tomorrow remains its original date after setup crosses midnight`() {
        val before = clock.copy(wallTime = LocalDateTime.of(2026, 11, 21, 23, 50).atZone(zone).toInstant().toEpochMilli())
        val draft = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.BEDTIME, before)
            .copy(packages = setOf("video")).chooseUntil(30, true, before)
        val after = before.at(before.wallTime + 90 * 60_000)
        val rule = draft.toRule(after)
        assertEquals(LocalDate.of(2026, 11, 22).toEpochDay(), draft.untilDateEpochDay)
        assertEquals(LocalDateTime.of(2026, 11, 22, 0, 30).atZone(zone).toInstant().toEpochMilli(), rule.endsAt)
        assertNotNull(RuleEngine.validate(rule))
    }

    @Test fun `explicitly setting the time again reanchors the date even when the clock value is unchanged`() {
        val original = RuleDraft.fresh(RuleKind.QUICK_BLOCK, LifeContext.STUDY, clock).chooseUntil(13 * 60, false, clock)
        val tomorrow = clock.at(clock.wallTime + 86_400_000)
        val corrected = original.chooseUntil(13 * 60, false, tomorrow)
        assertEquals(original.untilDateEpochDay!! + 1, corrected.untilDateEpochDay)
        assertEquals(original.untilMinute, corrected.untilMinute)
    }

    @Test fun `context presets affect routine hours without importing target apps`() {
        val bedtime = RuleDraft.fresh(RuleKind.BLOCK_DURING, LifeContext.BEDTIME, clock)
        val work = RuleDraft.fresh(RuleKind.BLOCK_DURING, LifeContext.WORK, clock)
        assertTrue(bedtime.packages.isEmpty())
        assertTrue(work.packages.isEmpty())
        assertEquals(TimeWindow(1350, 420, true), bedtime.windows.single())
        assertEquals((1..5).toSet(), work.days)
    }
}
