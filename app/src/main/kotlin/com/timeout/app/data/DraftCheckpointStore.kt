package com.timeout.app.data

import com.timeout.app.ui.RuleDraft
import com.timeout.core.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DraftScreen { BUILDER, APP_PICKER, SETUP }

@Serializable
data class DraftCheckpoint(
    val draft: RuleDraft? = null,
    val screen: DraftScreen = DraftScreen.BUILDER,
    val settingsReturn: DraftScreen? = null,
    val hasEdits: Boolean = false,
    val saving: Boolean = false,
    val revision: Long = 0,
    val updatedAt: Long = 0,
) {
    val resumable: Boolean get() = draft?.let { hasEdits || it.packages.isNotEmpty() || it.revision > 0 } == true
}

/** Raw persistence is a port so actual serialization/order/failure behavior is host-testable. */
interface DraftCheckpointPersistence {
    fun read(): String?
    fun write(value: String): Boolean
}

class DraftCheckpointStore(private val persistence: DraftCheckpointPersistence) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var writtenRevision: Long? = null

    suspend fun read(): DraftCheckpoint? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw = persistence.read() ?: return@withLock null
            json.decodeFromString<DraftCheckpoint>(raw).also { writtenRevision = it.revision }
        }
    }

    suspend fun write(checkpoint: DraftCheckpoint) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (writtenRevision == null) writtenRevision = persistence.read()?.let {
                runCatching { json.decodeFromString<DraftCheckpoint>(it).revision }.getOrDefault(0)
            } ?: 0
            // A queued older edit cannot overwrite a Settings checkpoint or a clear tombstone.
            if (checkpoint.revision < writtenRevision!!) return@withLock
            check(persistence.write(json.encodeToString(checkpoint))) { "Your draft could not be saved. Please try again before opening Android settings." }
            writtenRevision = checkpoint.revision
        }
    }
}

fun restoredDraftCheckpoint(durable: DraftCheckpoint?, saved: DraftCheckpoint?, legacy: RuleDraft?): DraftCheckpoint =
    listOfNotNull(durable, saved).maxByOrNull { it.revision }
        ?: legacy?.let { DraftCheckpoint(draft = it, hasEdits = true, revision = 1) } ?: DraftCheckpoint()

/** Never resume a draft that already committed before a crash, and never retry activation. */
fun DraftCheckpoint.reconcileSavedRules(rules: List<Rule>): DraftCheckpoint {
    val source = draft ?: return this
    val committed = rules.any { it.id == source.id && it.revision > source.revision }
    return when {
        committed && (saving || source.revision == 0L) -> copy(draft = null, settingsReturn = null, hasEdits = false, saving = false, revision = revision + 1)
        saving -> copy(saving = false, revision = revision + 1)
        else -> this
    }
}
