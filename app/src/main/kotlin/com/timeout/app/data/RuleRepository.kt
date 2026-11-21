package com.timeout.app.data

import androidx.room.withTransaction
import com.timeout.core.ClockSnapshot
import com.timeout.core.Commitment
import com.timeout.core.CommitmentPolicy
import com.timeout.core.Rule
import com.timeout.core.RuleEngine
import com.timeout.core.RuleKind
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

data class RuleState(val rules: List<Rule> = emptyList(), val unreadable: Int = 0)

class RuleRepository(private val database: TimeOutDatabase) {
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val state = dao.observeRules().map { records ->
        val rules = records.mapNotNull { decode(it) }
        RuleState(rules, records.size - rules.size)
    }
    val history = dao.observeHistory()
    val changes = dao.observeChanges()
    suspend fun snapshot(): RuleState {
        val records = dao.rules()
        val decoded = records.mapNotNull(::decode)
        return RuleState(decoded, records.size - decoded.size)
    }

    private fun decode(record: RuleRecord): Rule? = runCatching {
        // Old JSON omitted the former FLEXIBLE default. Do not upgrade its agreement silently.
        val element = json.parseToJsonElement(record.payload)
        val legacy = element is kotlinx.serialization.json.JsonObject && "commitment" !in element
        json.decodeFromString<Rule>(record.payload).let {
            it.copy(enabled = record.enabled, commitment = if (legacy) Commitment.FLEXIBLE else it.commitment)
        }
    }.getOrNull()

    private fun record(rule: Rule) = RuleRecord(rule.id, json.encodeToString(rule), rule.enabled, rule.createdAt)

    suspend fun save(rule: Rule, clock: ClockSnapshot): Boolean = database.withTransaction {
        RuleEngine.validate(rule)?.let { error(it) }
        val existing = dao.rule(rule.id)
        val previous = existing?.let(::decode)
        check(existing == null || previous != null) { "This protection could not be read. Its records have been preserved." }
        val replacement = rule.copy(revision = (previous?.revision ?: 0) + 1)
        if (previous != null) CommitmentPolicy.requireReplacement(previous, replacement, clock)
        val onlyNameChanged = previous != null && previous.copy(name = replacement.name, revision = replacement.revision) == replacement
        if (!onlyNameChanged) CommitmentPolicy.activationError(replacement, clock)?.let { error(it) }
        val others = dao.rules().filter { it.id != rule.id }.mapNotNull(::decode)
        RuleEngine.overlapError(others + replacement, clock)?.let { error(it) }
        if (previous != null && previous.commitment == Commitment.FIRM && RuleEngine.restricts(previous, clock) && !onlyNameChanged) {
            requestChange(previous, json.encodeToString(replacement), clock)
            return@withTransaction false
        }
        dao.putRule(record(replacement))
        dao.clearChange(rule.id)
        dao.event(HistoryRecord("save:${rule.id}:${replacement.revision}",
            if (previous == null) if (rule.kind == RuleKind.QUICK_BLOCK) "Session started" else "Routine saved" else "Protection updated",
            rule.name, clock.wallTime))
        dao.pruneHistory(clock.wallTime - 30L * 24 * 60 * 60_000)
        true
    }

    suspend fun setEnabled(id: String, enabled: Boolean, clock: ClockSnapshot): Boolean = database.withTransaction {
        val current = dao.rule(id)?.let(::decode) ?: error("This protection could not be read.")
        if (current.enabled == enabled) return@withTransaction true
        CommitmentPolicy.requireEditable(current, clock)
        if (enabled) CommitmentPolicy.activationError(current.copy(enabled = true), clock)?.let { error(it) }
        if (enabled) RuleEngine.validate(current.copy(enabled = true))?.let { error(it) }
        if (enabled) RuleEngine.overlapError(dao.rules().filter { it.id != id }.mapNotNull(::decode) + current.copy(enabled = true), clock)?.let { error(it) }
        if (enabled && current.kind == RuleKind.QUICK_BLOCK) check(RuleEngine.effectiveEnd(current, clock) > clock.wallTime) { "This session has ended. Start a new one instead." }
        if (!enabled && current.commitment == Commitment.FIRM && RuleEngine.restricts(current, clock)) {
            requestChange(current, null, clock)
            return@withTransaction false
        }
        dao.putRule(record(current.copy(enabled = enabled, revision = current.revision + 1)))
        dao.clearChange(id)
        dao.event(HistoryRecord(UUID.randomUUID().toString(), if (enabled) "Protection turned on" else "Protection ended", current.name, clock.wallTime))
        true
    }

    private suspend fun requestChange(rule: Rule, replacement: String?, clock: ClockSnapshot) {
        val existing = dao.change(rule.id)
        if (existing != null && existing.expectedRevision == rule.revision && existing.replacement == replacement && existing.expiresAt > clock.wallTime) return
        dao.putChange(PendingChange(rule.id, rule.revision, replacement,
            clock.wallTime + 60_000, clock.elapsedTime + 60_000, clock.bootCount, clock.wallTime + 360_000))
    }

    fun waitRemaining(change: PendingChange, clock: ClockSnapshot): Long =
        if (change.bootCount >= 0 && change.bootCount == clock.bootCount) change.readyElapsed - clock.elapsedTime
        else change.readyAt - clock.wallTime

    suspend fun confirmChange(id: String, clock: ClockSnapshot) = database.withTransaction {
        val change = dao.change(id) ?: error("Request this change again.")
        check(waitRemaining(change, clock) <= 0 && change.expiresAt > clock.wallTime) { "This request is not ready or has expired." }
        val current = dao.rule(id)?.let(::decode) ?: error("This protection could not be read.")
        CommitmentPolicy.requireEditable(current, clock)
        check(current.revision == change.expectedRevision) { "This protection changed. Review it again." }
        val next = change.replacement?.let { json.decodeFromString<Rule>(it) }
            ?: current.copy(enabled = false, revision = current.revision + 1)
        RuleEngine.validate(next)?.let { error(it) }
        CommitmentPolicy.activationError(next, clock)?.let { error(it) }
        RuleEngine.overlapError(dao.rules().filter { it.id != id }.mapNotNull(::decode) + next, clock)?.let { error(it) }
        dao.putRule(record(next))
        dao.clearChange(id)
        dao.event(HistoryRecord(UUID.randomUUID().toString(), "Requested change confirmed", current.name, clock.wallTime))
    }

    suspend fun cancelChange(id: String) = dao.clearChange(id)
    suspend fun pruneHistory(clock: ClockSnapshot) = dao.pruneHistory(clock.wallTime - 30L * 24 * 60 * 60_000)

    suspend fun recover(id: String?, clock: ClockSnapshot) = database.withTransaction {
        val records = dao.rules().filter { id == null || it.id == id }
        check(records.all { decode(it) != null }) { "Unreadable protection must be repaired before changing its records." }
        records.mapNotNull(::decode).forEach { CommitmentPolicy.requireEditable(it, clock) }
        if (id == null) {
            dao.disableAll()
            dao.clearChanges()
        } else {
            dao.rule(id)?.let { dao.putRule(it.copy(enabled = false)) }
            dao.clearChange(id)
        }
        dao.event(HistoryRecord(UUID.randomUUID().toString(), "Unlocked protection stopped",
            if (id == null) "All Time Out protection stopped. Saved settings kept." else "Selected protection stopped. Other routines still apply.", clock.wallTime))
    }

    suspend fun completeExpired(clock: ClockSnapshot) = database.withTransaction {
        dao.rules().mapNotNull(::decode).filter {
            it.enabled && it.kind == RuleKind.QUICK_BLOCK && RuleEngine.effectiveEnd(it, clock) <= clock.wallTime
        }.forEach {
            dao.putRule(record(it.copy(enabled = false)))
            dao.clearChange(it.id)
            dao.event(HistoryRecord("finished:${it.id}:${it.revision}", "Session finished", it.name, clock.wallTime))
        }
    }

    suspend fun deleteAll(clock: ClockSnapshot) = database.withTransaction {
        val records = dao.rules()
        check(records.all { decode(it) != null }) { "Unreadable protection must be repaired before deleting its records." }
        records.mapNotNull(::decode).forEach { CommitmentPolicy.requireEditable(it, clock) }
        dao.disableAll()
        dao.clearChanges()
        dao.clearRules()
        dao.clearHistory()
    }
}
