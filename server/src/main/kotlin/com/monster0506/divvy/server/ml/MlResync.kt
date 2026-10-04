package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

const val DEFAULT_CHECK_EVERY_MILLIS = 300_000L

/**
 * Fingerprint of a set of labels, equal to the ML service's `digest` for the same history: SHA-256 over
 * one line per observation sorted by id, `id|date|description|owner ids|member ids`, ids sorted and
 * comma-joined. A missed push, delete or correction shows up as a different digest.
 */
fun historyDigest(observations: List<MlObservation>): String {
    val text = observations.sortedBy { it.observationId }.joinToString("\n") { o ->
        listOf(
            o.observationId,
            o.purchasedOn,
            o.description,
            o.ownerIds.sorted().joinToString(","),
            o.memberIds.sorted().joinToString(","),
        ).joinToString("|")
    }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** How the ML service's copy of a household compares with the database. */
data class MlSyncStatus(
    val databaseLabels: Int,
    /** Null when the ML service could not be reached. */
    val mlLabels: Int?,
    /** Null when the ML service could not be reached. */
    val inSync: Boolean?,
    /** A push failed since the last successful repair. */
    val dirty: Boolean,
    val mlReady: Boolean?,
    val modelVersion: String?,
)

enum class SyncCheck {
    /** Checked just now and already identical. */
    IN_SYNC,

    /** Checked just now, was different, and has been rebuilt from the database. */
    REPAIRED,

    /** Nothing to do yet: not dirty and checked recently. */
    SKIPPED,

    /** The ML service could not be reached or refused the rebuild; the household stays marked for repair. */
    FAILED,
}

/**
 * Keeps the ML service's copy of a household equal to the database, which is the source of truth
 * (ADR 0002). Pushes can be missed, and the ML volume can be lost or restored, so the copy is checked
 * against the database before predicting and rebuilt with `replace` when it differs.
 */
class MlResync(
    private val ml: MlClient,
    private val receipts: ReceiptRepository,
    private val people: PersonRepository,
    private val sync: LabelSync,
    private val checkEveryMillis: Long = DEFAULT_CHECK_EVERY_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(MlResync::class.java)
    private val lastChecked = ConcurrentHashMap<Uuid, Long>()

    /** Every label the database holds for the household, as the ML service should have them. */
    suspend fun observationsFor(householdId: Uuid): List<MlObservation> {
        val members = people.listByHousehold(householdId, includeArchived = true)
        return receipts.listByHousehold(householdId, Int.MAX_VALUE)
            .filter { it.status == ReceiptStatus.CONFIRMED }
            .flatMap { observationsFor(it, members) }
    }

    suspend fun status(householdId: Uuid): MlSyncStatus {
        val local = observationsFor(householdId)
        val remote = (ml.history(householdId.toString()) as? MlResult.Success)?.value
        return MlSyncStatus(
            databaseLabels = local.size,
            mlLabels = remote?.historySize,
            inSync = remote?.let { matches(local, it) },
            dirty = householdId in sync.dirty,
            mlReady = remote?.ready,
            modelVersion = remote?.modelVersion,
        )
    }

    /** Replaces the ML copy with what the database holds, whether or not it looked different. */
    suspend fun resync(householdId: Uuid): MlResult<MlUpsertResponse> =
        sync.withHousehold(householdId) { rebuild(householdId, observationsFor(householdId)) }

    /** Called before predicting: repairs the household if it is marked dirty or has not been checked lately. */
    suspend fun ensureSynced(householdId: Uuid): SyncCheck {
        if (!isDue(householdId)) return SyncCheck.SKIPPED
        return sync.withHousehold(householdId) {
            if (!isDue(householdId)) return@withHousehold SyncCheck.SKIPPED
            val local = observationsFor(householdId)
            val remote = when (val result = ml.history(householdId.toString())) {
                is MlResult.Success -> result.value
                else -> return@withHousehold SyncCheck.FAILED
            }
            if (matches(local, remote)) {
                markChecked(householdId)
                SyncCheck.IN_SYNC
            } else {
                log.warn("ML history for household {} differs from the database; rebuilding it", householdId)
                if (rebuild(householdId, local) is MlResult.Success) SyncCheck.REPAIRED else SyncCheck.FAILED
            }
        }
    }

    private fun isDue(householdId: Uuid): Boolean {
        if (householdId in sync.dirty) return true
        val last = lastChecked[householdId] ?: return true
        return now() - last >= checkEveryMillis
    }

    private fun markChecked(householdId: Uuid) {
        lastChecked[householdId] = now()
        sync.markClean(householdId)
    }

    private suspend fun rebuild(householdId: Uuid, observations: List<MlObservation>): MlResult<MlUpsertResponse> {
        val result = ml.upsertObservations(householdId.toString(), observations, replace = true)
        if (result is MlResult.Success) markChecked(householdId) else sync.markDirty(householdId)
        return result
    }

    private fun matches(local: List<MlObservation>, remote: MlHistoryStatus): Boolean =
        if (remote.digest != null) remote.digest == historyDigest(local) else remote.historySize == local.size
}
