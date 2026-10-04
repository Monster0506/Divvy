package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.PersonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Keeps the ML service's copy of a household's confirmed labels current (ADR 0002). Confirming a
 * receipt pushes its items' owners; voiding one removes them. Pushes run in the background and
 * never fail the request that caused them: if the ML service is unavailable the household is
 * marked [dirty] so a later resync can repair it from the database, which is the source of truth.
 */
class LabelSync(
    private val ml: MlClient,
    private val people: PersonRepository,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(LabelSync::class.java)
    private val locks = ConcurrentHashMap<Uuid, Mutex>()
    private val tails = ConcurrentHashMap<Uuid, Job>()
    private val dirtyHouseholds: MutableSet<Uuid> = ConcurrentHashMap.newKeySet()

    /** Households whose ML copy may be behind the database. */
    val dirty: Set<Uuid> get() = dirtyHouseholds.toSet()

    fun markDirty(householdId: Uuid) {
        dirtyHouseholds += householdId
    }

    fun markClean(householdId: Uuid) {
        dirtyHouseholds -= householdId
    }

    /** Pushes a confirmed receipt's labels in the background. */
    fun scheduleConfirmed(receipt: Receipt): Job = launchFor(receipt.householdId) { pushConfirmed(receipt) }

    /** Removes a voided receipt's labels in the background. */
    fun scheduleVoided(receipt: Receipt): Job = launchFor(receipt.householdId) { removeVoided(receipt) }

    /** Runs [block] while no push or resync for the same household is running. */
    suspend fun <T> withHousehold(householdId: Uuid, block: suspend () -> T): T =
        locks.computeIfAbsent(householdId) { Mutex() }.withLock { block() }

    /** Waits for every push that has been scheduled so far. For tests and shutdown. */
    suspend fun awaitIdle() {
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    suspend fun pushConfirmed(receipt: Receipt) {
        if (receipt.status != ReceiptStatus.CONFIRMED) return
        val members = people.listByHousehold(receipt.householdId, includeArchived = true)
        val observations = observationsFor(receipt, members)
        if (observations.isEmpty()) return

        report(receipt.householdId, "push ${observations.size} labels") {
            ml.upsertObservations(receipt.householdId.toString(), observations)
        }
    }

    suspend fun removeVoided(receipt: Receipt) {
        val labelled = receipt.items.filter { it.labelSource != null }
        for (item in labelled) {
            report(receipt.householdId, "remove label ${item.id}", missingIsFine = true) {
                ml.deleteObservation(receipt.householdId.toString(), item.id.toString())
            }
        }
    }

    /**
     * Each household's pushes form a chain: a push starts only after the one scheduled before it has finished, so they
     * reach the ML service in the order they were made. The mutex alone could not promise that, because two coroutines
     * started together may reach it in either order.
     */
    private fun launchFor(householdId: Uuid, block: suspend () -> Unit): Job = synchronized(tails) {
        val previous = tails[householdId]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            withHousehold(householdId) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("ML label sync failed for household {}", householdId, e)
                    markDirty(householdId)
                }
            }
        }
        tails[householdId] = job
        job.invokeOnCompletion { tails.remove(householdId, job) }
        job.start()
        job
    }

    private suspend fun report(
        householdId: Uuid,
        what: String,
        missingIsFine: Boolean = false,
        call: suspend () -> MlResult<*>,
    ) {
        when (val result = call()) {
            is MlResult.Success -> Unit
            is MlResult.Unavailable -> {
                log.warn("ML service unavailable while trying to {} for household {}: {}", what, householdId, result.reason)
                markDirty(householdId)
            }
            is MlResult.Rejected ->
                if (!(missingIsFine && result.status == NOT_FOUND)) {
                    log.warn("ML service rejected {} for household {}: {}", what, householdId, result.message)
                }
        }
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}

/**
 * The labels of a confirmed receipt as ML observations. Items nobody owns are skipped. Members are
 * the people in the household when the receipt was confirmed, because they are who the labels
 * could have named: someone who joined later is unknown for these items, not a "no".
 */
fun observationsFor(receipt: Receipt, householdPeople: List<Person>): List<MlObservation> {
    val confirmedAt = receipt.confirmedAt ?: return emptyList()
    val inHousehold = householdPeople.filter {
        it.householdId == receipt.householdId &&
            it.createdAt <= confirmedAt &&
            (it.archivedAt == null || it.archivedAt > confirmedAt)
    }.map { it.id }.toSet()
    val purchasedOn = receipt.purchasedAt.atZone(ZoneOffset.UTC).toLocalDate().toString()

    return receipt.items.filter { it.owners.isNotEmpty() }.map { item ->
        val owners = item.owners.map { it.personId }
        MlObservation(
            observationId = item.id.toString(),
            purchasedOn = purchasedOn,
            description = item.description,
            memberIds = (inHousehold + owners).map { it.toString() }.sorted(),
            ownerIds = owners.map { it.toString() }.sorted(),
            source = item.mlSource(),
        )
    }
}

private fun ReceiptItem.mlSource(): MlAssignmentSource = when (labelSource) {
    LabelSource.PREDICTED_ACCEPTED -> MlAssignmentSource.PREDICTED_ACCEPTED
    LabelSource.PREDICTED_CORRECTED -> MlAssignmentSource.PREDICTED_CORRECTED
    LabelSource.MANUAL, null -> MlAssignmentSource.MANUAL
}
