package com.timeout.core

/** The single mutation gate, shared by every repository command and UI route. */
object CommitmentPolicy {
    fun endsAt(rule: Rule, clock: ClockSnapshot): Long = when {
        rule.kind == RuleKind.QUICK_BLOCK -> RuleEngine.effectiveEnd(rule, clock)
        rule.commitmentElapsedEndsAt > 0 && rule.bootCount >= 0 && rule.bootCount == clock.bootCount ->
            clock.wallTime + rule.commitmentElapsedEndsAt - clock.elapsedTime
        else -> rule.commitmentEndsAt
    }

    fun isLocked(rule: Rule, clock: ClockSnapshot): Boolean =
        rule.enabled && rule.commitment == Commitment.LOCKED && endsAt(rule, clock) > clock.wallTime

    fun requireEditable(rule: Rule, clock: ClockSnapshot) {
        check(!isLocked(rule, clock)) { "This commitment is locked until its confirmed end. It cannot be stopped or changed early." }
    }

    fun requireReplacement(previous: Rule, replacement: Rule, clock: ClockSnapshot) {
        // A label is cosmetic. Every policy field, selection and clock anchor stays immutable.
        val nameOnly = previous.copy(name = replacement.name, revision = replacement.revision) == replacement
        if (!nameOnly) requireEditable(previous, clock)
    }

    fun activationError(rule: Rule, clock: ClockSnapshot): String? {
        if (!rule.enabled) return null
        if (rule.commitment != Commitment.LOCKED) return "Review a locked commitment before turning this protection on."
        if (endsAt(rule, clock) <= clock.wallTime) return "Choose a commitment end in the future."
        if (rule.kind != RuleKind.QUICK_BLOCK && rule.commitmentEndsAt <= clock.wallTime) return "Choose the date when routine settings unlock."
        return null
    }
}
