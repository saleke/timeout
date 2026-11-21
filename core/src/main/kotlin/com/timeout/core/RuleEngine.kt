package com.timeout.core

import java.time.LocalDate
import java.time.ZoneId

/** Pure policy. Native permissions and observed enforcement are deliberately separate. */
object RuleEngine {
    private const val HORIZON_DAYS = 15L

    fun effectiveEnd(rule: Rule, clock: ClockSnapshot): Long =
        if (rule.elapsedEndsAt > 0 && rule.bootCount >= 0 && rule.bootCount == clock.bootCount) {
            clock.wallTime + rule.elapsedEndsAt - clock.elapsedTime
        } else {
            rule.endsAt
        }

    fun restricts(rule: Rule, clock: ClockSnapshot): Boolean {
        if (!rule.enabled) return false
        if (rule.kind == RuleKind.QUICK_BLOCK) {
            val anchored = rule.elapsedEndsAt > 0 && rule.bootCount >= 0 && rule.bootCount == clock.bootCount
            val started = if (anchored) clock.elapsedTime >= rule.elapsedEndsAt - (rule.endsAt - rule.startedAt)
                else rule.startedAt <= clock.wallTime
            return started && effectiveEnd(rule, clock) > clock.wallTime
        }
        val zone = runCatching { ZoneId.of(rule.zoneId) }.getOrNull() ?: return false
        val today = clock.wallTime.inZone(zone).toLocalDate()
        val insideWindow = listOf(today.minusDays(1), today).any { date ->
            date.dayOfWeek.value in rule.days && rule.windows.any { window ->
                val (start, end) = interval(date, window, zone)
                clock.wallTime >= start && clock.wallTime < end
            }
        }
        return when (rule.kind) {
            RuleKind.BLOCK_DURING -> insideWindow
            RuleKind.ALLOW_ONLY -> today.dayOfWeek.value in rule.days && !insideWindow
            RuleKind.QUICK_BLOCK -> false
        }
    }

    fun decision(packageName: String, rules: List<Rule>, clock: ClockSnapshot): AccessDecision {
        val applicable = rules.filter { it.enabled && packageName in it.packages }
        val restrictions = applicable.filter { restricts(it, clock) }.map { rule ->
            Restriction(rule, transitions(rule, clock).firstOrNull { !restricts(rule, clock.at(it)) })
        }
        if (restrictions.isEmpty()) return AccessDecision(emptyList(), null)
        val nextAccess = applicable.flatMap { transitions(it, clock) }.distinct().sorted()
            .firstOrNull { instant -> applicable.none { restricts(it, clock.at(instant)) } }
        return AccessDecision(restrictions, nextAccess)
    }

    fun transitions(rule: Rule, clock: ClockSnapshot): List<Long> {
        if (!rule.enabled) return emptyList()
        if (rule.kind == RuleKind.QUICK_BLOCK) {
            return listOf(rule.startedAt, effectiveEnd(rule, clock)).filter { it > clock.wallTime }.sorted()
        }
        val zone = runCatching { ZoneId.of(rule.zoneId) }.getOrNull() ?: return emptyList()
        val today = clock.wallTime.inZone(zone).toLocalDate()
        return buildList {
            // The commitment changes editability, not the recurring schedule's allow/deny state.
            if (rule.commitment == Commitment.LOCKED && rule.commitmentEndsAt > 0) add(CommitmentPolicy.endsAt(rule, clock))
            for (offset in -1L..HORIZON_DAYS) {
                val date = today.plusDays(offset)
                // Midnight also changes selected-day applicability for available-only plans.
                add(date.atStartOfDay(zone).toInstant().toEpochMilli())
                if (date.dayOfWeek.value in rule.days) rule.windows.forEach { window ->
                    val (start, end) = interval(date, window, zone)
                    add(start)
                    add(end)
                }
            }
        }.filter { it > clock.wallTime }.distinct().sorted()
    }

    fun nextBoundary(rules: List<Rule>, clock: ClockSnapshot): Long? =
        rules.flatMap { transitions(it, clock) }.minOrNull()

    fun upcoming(rules: List<Rule>, clock: ClockSnapshot, limit: Int = 5): List<ScheduleEvent> =
        rules.filter { it.enabled }.flatMap { rule ->
            transitions(rule, clock).mapNotNull { instant ->
                val before = restricts(rule, clock.at(instant - 1))
                val after = restricts(rule, clock.at(instant))
                if (before == after) null else ScheduleEvent(instant, rule, after)
            }
        }.sortedBy { it.at }.take(limit)

    fun validate(rule: Rule): String? {
        if (rule.packages.isEmpty()) return "Choose at least one app."
        if (rule.name.isBlank()) return "Give this protection a name."
        if (rule.kind == RuleKind.QUICK_BLOCK) {
            return if (rule.endsAt <= rule.startedAt || rule.endsAt - rule.startedAt > 86_400_000)
                "Choose a duration greater than zero and no longer than 24 hours." else null
        }
        if (rule.days.isEmpty() || rule.days.any { it !in 1..7 }) return "Choose at least one repeat day."
        val zone = runCatching { ZoneId.of(rule.zoneId) }.getOrNull() ?: return "Choose a valid time zone."
        if (rule.windows.isEmpty() || rule.windows.size > 8) return "Add between one and eight time windows."
        if (rule.windows.any {
                it.startMinute !in 0..1439 || it.endMinute !in 0..1439 ||
                    it.durationMinutes() <= 0 || it.durationMinutes() > 1440
            }) return "Check the end time and the next-day setting."
        val monday = LocalDate.of(2026, 1, 5).atStartOfDay(zone).toInstant().toEpochMilli()
        val sample = ClockSnapshot(monday)
        val validationRule = rule.copy(enabled = true, commitmentEndsAt = 0, commitmentElapsedEndsAt = 0)
        val candidates = listOf(monday) + transitions(validationRule, sample)
        if (candidates.all { restricts(validationRule, sample.at(it)) }) return "Leave an access opportunity in the week."
        return null
    }

    fun overlapError(rules: List<Rule>, clock: ClockSnapshot): String? =
        rules.filter { it.enabled }.flatMap { it.packages }.distinct().firstNotNullOfOrNull { name ->
            val decision = decision(name, rules, clock)
            if (decision.restricted && decision.nextAccessAt == null)
                "These routines leave an app without a shared access window in the next two weeks. Adjust the overlap before committing."
            else null
        }

    private fun interval(date: LocalDate, window: TimeWindow, zone: ZoneId): Pair<Long, Long> {
        val endDate = if (window.endsNextDay) date.plusDays(1) else date
        // java.time shifts missing local times across a DST gap and chooses the first repeated offset.
        val start = date.atStartOfDay().plusMinutes(window.startMinute.toLong()).atZone(zone)
        val end = endDate.atStartOfDay().plusMinutes(window.endMinute.toLong()).atZone(zone)
        return start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
    }
}
