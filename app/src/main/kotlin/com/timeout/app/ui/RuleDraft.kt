package com.timeout.app.ui

import com.timeout.core.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class RuleDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val context: LifeContext = LifeContext.STUDY,
    val kind: RuleKind = RuleKind.QUICK_BLOCK,
    val packages: Set<String> = emptySet(),
    val minutes: Int = 45,
    val untilTime: Boolean = false,
    val untilMinute: Int = 17 * 60,
    val untilTomorrow: Boolean = false,
    val days: Set<Int> = (1..7).toSet(),
    val windows: List<TimeWindow> = listOf(TimeWindow(9 * 60, 17 * 60)),
    val commitment: Commitment = Commitment.LOCKED,
    val createdAt: Long = 0,
    val revision: Long = 0,
    val zoneId: String = ZoneId.systemDefault().id,
    val enabled: Boolean = true,
    val commitmentDateEpochDay: Long = LocalDate.now().plusDays(7).toEpochDay(),
    // A chosen Today/Tomorrow is a date, not an instruction to recalculate after setup.
    val untilDateEpochDay: Long? = null,
) {
    fun chooseUntil(minute: Int, tomorrow: Boolean, clock: ClockSnapshot): RuleDraft = copy(
        untilTime = true, untilMinute = minute, untilTomorrow = tomorrow,
        untilDateEpochDay = clock.wallTime.inZone(ZoneId.systemDefault()).toLocalDate().plusDays(if (tomorrow) 1 else 0).toEpochDay())

    fun toRule(clock: ClockSnapshot, enabled: Boolean = this.enabled): Rule {
        val zone = ZoneId.of(zoneId)
        val endDate = untilDateEpochDay?.let(LocalDate::ofEpochDay)
            ?: clock.wallTime.inZone(ZoneId.systemDefault()).toLocalDate().plusDays(if (untilTomorrow) 1 else 0)
        val end = if (untilTime) endDate.atStartOfDay().plusMinutes(untilMinute.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else clock.wallTime + minutes * 60_000L
        val commitmentEnd = LocalDate.ofEpochDay(commitmentDateEpochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        return Rule(id = id, name = name.trim().ifEmpty { "${context.displayName()} ${if (kind == RuleKind.QUICK_BLOCK) "session" else "routine"}" },
            context = context, packages = packages.toList(), kind = kind, enabled = enabled, commitment = Commitment.LOCKED,
            days = days, windows = windows, zoneId = zoneId,
            startedAt = if (kind == RuleKind.QUICK_BLOCK) clock.wallTime else 0,
            endsAt = if (kind == RuleKind.QUICK_BLOCK) end else 0,
            elapsedEndsAt = if (kind == RuleKind.QUICK_BLOCK) clock.elapsedTime + end - clock.wallTime else 0,
            bootCount = clock.bootCount, createdAt = createdAt.takeIf { it > 0 } ?: clock.wallTime, revision = revision,
            commitmentEndsAt = if (kind == RuleKind.QUICK_BLOCK) 0 else commitmentEnd,
            commitmentElapsedEndsAt = if (kind == RuleKind.QUICK_BLOCK) 0 else clock.elapsedTime + commitmentEnd - clock.wallTime)
    }

    companion object {
        /** A new session never inherits targets, clock anchors, or duration from saved rules. */
        fun fresh(kind: RuleKind, context: LifeContext, clock: ClockSnapshot): RuleDraft = RuleDraft(
            kind = kind, context = context, packages = emptySet(),
            windows = when {
                context == LifeContext.BEDTIME -> listOf(TimeWindow(22 * 60 + 30, 7 * 60, true))
                kind == RuleKind.ALLOW_ONLY -> listOf(TimeWindow(12 * 60 + 30, 13 * 60), TimeWindow(19 * 60, 19 * 60 + 30))
                else -> listOf(TimeWindow(9 * 60, 17 * 60))
            }, days = if (context == LifeContext.WORK) (1..5).toSet() else (1..7).toSet(),
            commitmentDateEpochDay = clock.wallTime.inZone(ZoneId.systemDefault()).toLocalDate().plusDays(7).toEpochDay())
    }
}
