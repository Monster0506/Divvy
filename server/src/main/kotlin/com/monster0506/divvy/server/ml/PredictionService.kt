package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ItemOwnerRepository
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.uuid.Uuid

enum class PredictionState {
    /** The model named owners, and they were stored on the item. */
    PREDICTED,

    /** The model did not recognise the item, so it was left unassigned. */
    UNKNOWN,

    /** A person already chose the owners, so the prediction was recorded but not applied. */
    USER_ASSIGNED,

    /** No prediction was made, because the ML service could not be used. */
    UNAVAILABLE,
}

data class ItemPredictionView(
    val itemId: Uuid,
    val state: PredictionState,
    val ownerIds: List<Uuid>,
    /** The model's confidence, or null when there is no prediction. */
    val confidence: Double?,
    val alternatives: List<StoredAlternative>,
    /** True when the owners are the model's and are not sure enough to apply without a person confirming. */
    val needsConfirmation: Boolean = false,
)

data class ReceiptPredictions(
    val receiptId: Uuid,
    val mlAvailable: Boolean,
    val modelVersion: String?,
    /** Set when predictions could not be made, so the app can tell the person to assign by hand. */
    val notice: String?,
    val items: List<ItemPredictionView>,
)

sealed interface PredictionResult {
    data object NotFound : PredictionResult

    /** Only a draft receipt can be pre-assigned; a confirmed one is already decided. */
    data object NotDraft : PredictionResult

    data class Done(val predictions: ReceiptPredictions) : PredictionResult
}

sealed interface AcceptResult {
    data object NotFound : AcceptResult

    data object NotDraft : AcceptResult

    /** The items whose model owners were confirmed as they were. */
    data class Done(val itemIds: List<Uuid>) : AcceptResult
}

/**
 * Asks the ML service who owns the items on a draft receipt and stores the answer as model-sourced owners
 * with their confidence. A person's choice always wins: an item someone has assigned is never overwritten.
 * If the ML service cannot be used nothing is changed and the result says so, so the person assigns by hand.
 */
class PredictionService(
    private val ml: MlClient,
    private val resync: MlResync,
    private val receipts: ReceiptRepository,
    private val people: PersonRepository,
    private val itemOwners: ItemOwnerRepository,
    private val predictions: PredictionRepository,
    private val settings: MlSettingsRepository,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(PredictionService::class.java)

    suspend fun predictFor(receiptId: Uuid): PredictionResult {
        val receipt = receipts.findById(receiptId) ?: return PredictionResult.NotFound
        if (receipt.status != ReceiptStatus.DRAFT) return PredictionResult.NotDraft
        if (receipt.items.isEmpty()) return PredictionResult.Done(ReceiptPredictions(receiptId, true, null, null, emptyList()))

        val members = people.listByHousehold(receipt.householdId)
        val rules = settings.get(receipt.householdId)
        resync.ensureSynced(receipt.householdId)
        val request = MlPredictRequest(
            householdId = receipt.householdId.toString(),
            people = members.map { MlPerson(it.id.toString(), it.displayName) },
            items = receipt.items.map { MlItem(it.id.toString(), it.description) },
        )

        return when (val result = ml.predict(request)) {
            is MlResult.Success -> PredictionResult.Done(apply(receipt, members, result.value, rules))
            is MlResult.Unavailable -> PredictionResult.Done(unavailable(receipt, result.reason))
            is MlResult.Rejected -> PredictionResult.Done(unavailable(receipt, "the ML service refused the request: ${result.message}"))
        }
    }

    /** The predictions already stored for a receipt, next to what each item's owners are now. */
    suspend fun stored(receiptId: Uuid): PredictionResult {
        val receipt = receipts.findById(receiptId) ?: return PredictionResult.NotFound
        val byItem = predictions.forReceipt(receiptId).associateBy { it.receiptItemId }
        val rules = settings.get(receipt.householdId)
        val items = receipt.items.map { item ->
            val saved = byItem[item.id]
            val owners = item.owners.map { it.personId }
            val state = when {
                item.owners.any { it.source == OwnershipSource.USER } -> PredictionState.USER_ASSIGNED
                saved == null -> PredictionState.UNAVAILABLE
                saved.status == MlPredictionStatus.PREDICTED -> PredictionState.PREDICTED
                else -> PredictionState.UNKNOWN
            }
            ItemPredictionView(item.id, state, owners, saved?.confidence, saved?.alternatives.orEmpty(), rules.needsConfirmation(item))
        }
        return PredictionResult.Done(
            ReceiptPredictions(receiptId, true, byItem.values.firstOrNull()?.modelVersion, null, items),
        )
    }

    private suspend fun apply(receipt: Receipt, members: List<Person>, response: MlPredictResponse, rules: MlSettings): ReceiptPredictions {
        val memberIds = members.map { it.id }.toSet()
        val byItem = response.predictions.associateBy { it.itemId }
        val saved = mutableListOf<StoredPrediction>()
        val views = receipt.items.map { item ->
            val prediction = byItem[item.id.toString()]
            val owners = prediction?.takeIf { it.status == MlPredictionStatus.PREDICTED }
                ?.owners.orEmpty().mapNotNull(::parseId).filter { it in memberIds }.distinct()
            val known = owners.isNotEmpty() && prediction != null
            val confidence = if (known) (prediction?.confidence ?: 0.0).coerceIn(0.0, 1.0) else 0.0
            val alternatives = prediction?.alternatives.orEmpty().mapNotNull { alt ->
                alt.owners.mapNotNull(::parseId).filter { it in memberIds }.takeIf { it.isNotEmpty() }
                    ?.let { StoredAlternative(it, alt.confidence.coerceIn(0.0, 1.0)) }
            }.takeIf { known }.orEmpty()

            saved += StoredPrediction(
                item.id, receipt.householdId, response.modelVersion,
                if (known) MlPredictionStatus.PREDICTED else MlPredictionStatus.UNKNOWN,
                confidence, alternatives, now(),
            )
            val state = stateOf(item, known)
            val pending = state == PredictionState.PREDICTED && (!rules.autoAssign || stored4(confidence) < rules.autoAssignThreshold)
            ItemPredictionView(item.id, state, ownersAfter(item, owners, confidence, known), confidence.takeIf { known }, alternatives, pending)
        }
        predictions.save(saved)
        log.info(
            "Predicted receipt {}: {} predicted, {} unknown, {} already assigned by a person",
            receipt.id,
            views.count { it.state == PredictionState.PREDICTED },
            views.count { it.state == PredictionState.UNKNOWN },
            views.count { it.state == PredictionState.USER_ASSIGNED },
        )
        return ReceiptPredictions(receipt.id, true, response.modelVersion, null, views)
    }

    /** Confirms the model's owners as they are, for the items that need it (all of them when [itemIds] is null). */
    suspend fun accept(receiptId: Uuid, itemIds: Set<Uuid>?): AcceptResult {
        val receipt = receipts.findById(receiptId) ?: return AcceptResult.NotFound
        if (receipt.status != ReceiptStatus.DRAFT) return AcceptResult.NotDraft
        val rules = settings.get(receipt.householdId)
        val accepted = receipt.items.filter { rules.needsConfirmation(it) && (itemIds == null || it.id in itemIds) }
        accepted.forEach { item ->
            itemOwners.setOwners(item.id, item.owners.map { OwnerShare(it.personId, it.shareWeight, OwnershipSource.USER) })
        }
        return AcceptResult.Done(accepted.map { it.id })
    }

    private fun stateOf(item: ReceiptItem, known: Boolean): PredictionState = when {
        item.owners.any { it.source == OwnershipSource.USER } -> PredictionState.USER_ASSIGNED
        known -> PredictionState.PREDICTED
        else -> PredictionState.UNKNOWN
    }

    /** Writes the model's owners unless a person already chose some; clears a stale model guess if it now has none. */
    private suspend fun ownersAfter(item: ReceiptItem, owners: List<Uuid>, confidence: Double, known: Boolean): List<Uuid> {
        if (item.owners.any { it.source == OwnershipSource.USER }) return item.owners.map { it.personId }
        if (!known) {
            if (item.owners.isNotEmpty()) itemOwners.setOwners(item.id, emptyList())
            return emptyList()
        }

        val score = BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP)
        itemOwners.setOwners(item.id, owners.map { OwnerShare(it, 1, OwnershipSource.MODEL, score) })
        return owners
    }

    private fun unavailable(receipt: Receipt, reason: String): ReceiptPredictions {
        log.warn("No predictions for receipt {}: {}", receipt.id, reason)
        return ReceiptPredictions(
            receiptId = receipt.id,
            mlAvailable = false,
            modelVersion = null,
            notice = "Predictions are not available right now, so items were not pre-assigned. Assign them by hand.",
            items = receipt.items.map { item ->
                val owners = item.owners.map { it.personId }
                val state = if (item.owners.any { it.source == OwnershipSource.USER }) PredictionState.USER_ASSIGNED else PredictionState.UNAVAILABLE
                ItemPredictionView(item.id, state, owners, null, emptyList())
            },
        )
    }

    /** The confidence as it is stored on the owner row, so the decision matches what confirm sees later. */
    private fun stored4(confidence: Double) = Math.round(confidence * 10_000) / 10_000.0

    private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()
}
