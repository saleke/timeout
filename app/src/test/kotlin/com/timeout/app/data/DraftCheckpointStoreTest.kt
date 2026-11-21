package com.timeout.app.data

import com.timeout.app.ui.RuleDraft
import com.timeout.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftCheckpointStoreTest {
    private class Disk : DraftCheckpointPersistence {
        var raw: String? = null
        var writable = true
        override fun read() = raw
        override fun write(value: String): Boolean {
            if (!writable) return false
            raw = value
            return true
        }
    }

    private val draft = RuleDraft(id = "pending-session", name = "Evening", context = LifeContext.BEDTIME,
        packages = setOf("app.video", "app.social"), minutes = 95, untilTime = true, untilMinute = 60,
        untilTomorrow = true, untilDateEpochDay = 20_900, days = setOf(1, 3, 7),
        windows = listOf(TimeWindow(1350, 420, true), TimeWindow(900, 930)), zoneId = "Europe/London",
        commitmentDateEpochDay = 20_914, createdAt = 100, revision = 2)

    @Test fun `settings handoff preserves every selected and edited field after a fresh store is created`() = runBlocking {
        val disk = Disk()
        val checkpoint = DraftCheckpoint(draft, DraftScreen.SETUP, DraftScreen.BUILDER, hasEdits = true, revision = 7, updatedAt = 900)
        DraftCheckpointStore(disk).write(checkpoint)
        val recovered = DraftCheckpointStore(disk).read()!!
        assertEquals(checkpoint, recovered)
        assertEquals(setOf("app.video", "app.social"), recovered.draft!!.packages)
        assertEquals(DraftScreen.BUILDER, recovered.settingsReturn)
    }

    @Test fun `older queued input cannot overwrite the checkpoint written before Android settings`() = runBlocking {
        val disk = Disk()
        val store = DraftCheckpointStore(disk)
        val handoff = DraftCheckpoint(draft, settingsReturn = DraftScreen.BUILDER, revision = 8)
        store.write(handoff)
        store.write(DraftCheckpoint(draft.copy(packages = emptySet(), minutes = 45), revision = 3))
        assertEquals(handoff, DraftCheckpointStore(disk).read())
    }

    @Test fun `clear tombstone prevents an earlier activity snapshot from resurrecting a finished draft`() = runBlocking {
        val disk = Disk()
        val old = DraftCheckpoint(draft, revision = 4)
        val cleared = DraftCheckpoint(revision = 5)
        DraftCheckpointStore(disk).write(cleared)
        val restarted = DraftCheckpointStore(disk)
        restarted.write(old)
        assertNull(restoredDraftCheckpoint(restarted.read(), old, draft).draft)
    }

    @Test fun `a newer transient edit wins without losing the pending return destination`() {
        val durable = DraftCheckpoint(draft, revision = 10)
        val saved = durable.copy(draft = draft.copy(minutes = 125), settingsReturn = DraftScreen.BUILDER, revision = 11)
        assertEquals(saved, restoredDraftCheckpoint(durable, saved, null))
    }

    @Test fun `legacy saved state is migrated only when no checkpoint or tombstone exists`() {
        assertEquals(draft, restoredDraftCheckpoint(null, null, draft).draft)
        assertNull(restoredDraftCheckpoint(DraftCheckpoint(revision = 1), null, draft).draft)
    }

    @Test fun `failed durable write is reported and does not replace the previous checkpoint`() = runBlocking {
        val disk = Disk()
        val previous = DraftCheckpoint(draft, revision = 4)
        val store = DraftCheckpointStore(disk)
        store.write(previous)
        disk.writable = false
        try {
            store.write(previous.copy(settingsReturn = DraftScreen.BUILDER, revision = 5))
            fail("Settings must not open after an unacknowledged checkpoint")
        } catch (_: IllegalStateException) { /* handoff remains blocked */ }
        assertEquals(previous, DraftCheckpointStore(disk).read())
    }

    @Test fun `interrupted activation before a database save restores a draft and never retries activation`() {
        val checkpoint = DraftCheckpoint(draft, saving = true, revision = 10)
        val restored = checkpoint.reconcileSavedRules(emptyList())
        assertEquals(draft, restored.draft)
        assertFalse(restored.saving)
    }

    @Test fun `activation committed before a crash consumes the checkpoint instead of starting another timeout`() {
        val source = draft.copy(revision = 0)
        val committed = Rule(source.id, "Saved", packages = source.packages.toList(), kind = RuleKind.QUICK_BLOCK, revision = 1)
        val checkpoint = DraftCheckpoint(source, settingsReturn = DraftScreen.BUILDER, saving = true, revision = 4)
        val restored = checkpoint.reconcileSavedRules(listOf(committed))
        assertNull(restored.draft)
        assertNull(restored.settingsReturn)
        assertFalse(restored.saving)
    }

    @Test fun `a new-session draft already saved by an older app version does not reappear`() {
        val source = draft.copy(revision = 0)
        val saved = Rule(source.id, "Existing", packages = source.packages.toList(), kind = RuleKind.QUICK_BLOCK, revision = 1)
        assertNull(DraftCheckpoint(source, revision = 1).reconcileSavedRules(listOf(saved)).draft)
    }

    @Test fun `unsaved edits to a changed routine remain available for explicit review`() {
        val changed = Rule(draft.id, "Updated elsewhere", packages = listOf("other.app"), kind = RuleKind.BLOCK_DURING, revision = 3)
        val checkpoint = DraftCheckpoint(draft, revision = 4)
        assertEquals(checkpoint, checkpoint.reconcileSavedRules(listOf(changed)))
    }
}
