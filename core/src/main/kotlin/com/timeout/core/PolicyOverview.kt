package com.timeout.core

/** A presentation snapshot, computed off-main by the Android client. Never an enforcement timer. */
data class PolicyOverview(
    val clock: ClockSnapshot,
    val activeRuleIds: Set<String> = emptySet(),
    val lockedRuleIds: Set<String> = emptySet(),
    val decisions: Map<String, AccessDecision> = emptyMap(),
    val applicableRuleIds: Map<String, List<String>> = emptyMap(),
    val upcoming: List<ScheduleEvent> = emptyList(),
    val nextBoundaryAt: Long? = null,
    val sourceRules: List<Rule> = emptyList(),
) {
    companion object {
        fun evaluate(rules: List<Rule>, clock: ClockSnapshot): PolicyOverview {
            val enabled = rules.filter { it.enabled }
            val byPackage = linkedMapOf<String, MutableList<Rule>>()
            enabled.forEach { rule -> rule.packages.distinct().forEach { name -> byPackage.getOrPut(name) { mutableListOf() }.add(rule) } }
            // Apps sharing exactly the same applicable rules share policy work, not identity.
            val cache = mutableMapOf<List<String>, AccessDecision>()
            val decisions = byPackage.mapValues { (name, applicable) ->
                cache.getOrPut(applicable.map { it.id }) { RuleEngine.decision(name, applicable, clock) }
            }
            return PolicyOverview(clock,
                activeRuleIds = enabled.filter { RuleEngine.restricts(it, clock) }.map { it.id }.toSet(),
                lockedRuleIds = enabled.filter { CommitmentPolicy.isLocked(it, clock) }.map { it.id }.toSet(),
                decisions = decisions,
                applicableRuleIds = byPackage.mapValues { (_, applicable) -> applicable.map { it.id } },
                upcoming = RuleEngine.upcoming(enabled, clock, 4),
                nextBoundaryAt = RuleEngine.nextBoundary(enabled, clock), sourceRules = rules)
        }
    }
}

/** Anchors a displayed forecast so digits do not jump while a new clock-change projection loads. */
data class DisplayDeadline(val wallTime: Long, val elapsedTime: Long, val bootCount: Int) {
    fun remaining(clock: ClockSnapshot): Long =
        (if (bootCount >= 0 && bootCount == clock.bootCount) elapsedTime - clock.elapsedTime
         else wallTime - clock.wallTime).coerceAtLeast(0)

    companion object {
        fun at(wallTime: Long, clock: ClockSnapshot) =
            DisplayDeadline(wallTime, clock.elapsedTime + wallTime - clock.wallTime, clock.bootCount)
    }
}
