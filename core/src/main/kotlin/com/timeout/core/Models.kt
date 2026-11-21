package com.timeout.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
enum class LifeContext { STUDY, WORK, BEDTIME, PERSONAL }

@Serializable
enum class RuleKind { QUICK_BLOCK, BLOCK_DURING, ALLOW_ONLY }

@Serializable
// Retain legacy values so an upgrade never silently changes an earlier agreement.
enum class Commitment { FLEXIBLE, FIRM, LOCKED }

@Serializable
data class TimeWindow(val startMinute: Int, val endMinute: Int, val endsNextDay: Boolean = false)

fun TimeWindow.durationMinutes(): Int = (endMinute + if (endsNextDay) 1440 else 0) - startMinute

@Serializable
data class Rule(
    val id: String,
    val name: String,
    val context: LifeContext = LifeContext.PERSONAL,
    val packages: List<String>,
    val kind: RuleKind,
    val enabled: Boolean = true,
    val commitment: Commitment = Commitment.LOCKED,
    val days: Set<Int> = (1..7).toSet(),
    val windows: List<TimeWindow> = emptyList(),
    val zoneId: String = "UTC",
    val startedAt: Long = 0,
    val endsAt: Long = 0,
    val elapsedEndsAt: Long = 0,
    val bootCount: Int = -1,
    val createdAt: Long = 0,
    val revision: Long = 1,
    val commitmentEndsAt: Long = 0,
    val commitmentElapsedEndsAt: Long = 0,
)

data class ClockSnapshot(val wallTime: Long, val elapsedTime: Long = 0, val bootCount: Int = -1) {
    fun at(instant: Long) = copy(wallTime = instant, elapsedTime = elapsedTime + instant - wallTime)
}

data class Restriction(val rule: Rule, val releasesAt: Long?)

data class AccessDecision(val restrictions: List<Restriction>, val nextAccessAt: Long?) {
    val restricted: Boolean get() = restrictions.isNotEmpty()
}

data class ScheduleEvent(val at: Long, val rule: Rule, val restrictionBegins: Boolean)

fun Long.inZone(zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(this).atZone(zone)
