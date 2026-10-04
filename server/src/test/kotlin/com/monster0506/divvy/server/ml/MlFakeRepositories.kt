package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.repository.ConfirmResult

import com.monster0506.divvy.server.domain.ReceiptCursor
import com.monster0506.divvy.server.domain.ReceiptFilter
import com.monster0506.divvy.server.domain.ReceiptPage

import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ProcessedReceipt
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlin.uuid.Uuid

/** Serves a fixed list of receipts; only listing is used by the resync. */
class FakeReceipts(@Volatile var all: List<Receipt>) : ReceiptRepository {
    override suspend fun create(receipt: NewReceipt): Receipt = error("not used")

    override suspend fun createProcessing(householdId: Uuid, imageKey: String): Receipt = error("not used")

    val completed = java.util.concurrent.CopyOnWriteArrayList<Pair<Uuid, ProcessedReceipt>>()

    override suspend fun completeProcessing(id: Uuid, processed: ProcessedReceipt): Receipt? =
        change(id, ReceiptStatus.PROCESSING) { it.copy(
                status = ReceiptStatus.DRAFT,
                merchantName = processed.merchantName,
                items = processed.items.mapIndexed { i, line -> item(id, line.description, emptyList(), null, i + 1) },
            )
        }
            ?.also { completed += id to processed }

    override suspend fun failProcessing(id: Uuid, reason: String): Receipt? =
        change(id, ReceiptStatus.PROCESSING) { it.copy(status = ReceiptStatus.FAILED, failureReason = reason) }

    override suspend fun retryProcessing(id: Uuid): Receipt? =
        change(id, ReceiptStatus.FAILED) { it.copy(status = ReceiptStatus.PROCESSING, failureReason = null) }

    override suspend fun idsWithStatus(status: ReceiptStatus): List<Uuid> = all.filter { it.status == status }.map { it.id }

    // Receipts are read in parallel, so the read-modify-write must not interleave.
    private fun change(id: Uuid, from: ReceiptStatus, apply: (Receipt) -> Receipt): Receipt? = synchronized(this) {
        val current = all.firstOrNull { it.id == id && it.status == from } ?: return null
        apply(current).also { updated -> all = all.map { if (it.id == id) updated else it } }
    }

    override suspend fun findById(id: Uuid): Receipt? = all.firstOrNull { it.id == id }

    override suspend fun listByHousehold(householdId: Uuid, limit: Int): List<Receipt> =
        all.filter { it.householdId == householdId }.take(limit)

    override suspend fun page(
        householdId: Uuid,
        filter: ReceiptFilter,
        after: ReceiptCursor?,
        limit: Int,
    ): ReceiptPage = error("not used")

    override suspend fun update(id: Uuid, patch: ReceiptPatch): Receipt? = error("not used")

    override suspend fun addItem(receiptId: Uuid, item: NewReceiptItem): Receipt? = error("not used")

    override suspend fun updateItem(receiptId: Uuid, itemId: Uuid, patch: ReceiptItemPatch): Receipt? = error("not used")

    override suspend fun deleteItem(receiptId: Uuid, itemId: Uuid): Receipt? = error("not used")

    override suspend fun mergeItems(receiptId: Uuid, itemIds: List<Uuid>, description: String?): Receipt? = error("not used")

    override suspend fun confirm(id: Uuid): Receipt? = error("not used")

    override suspend fun confirmAssigned(id: Uuid): ConfirmResult = error("not used")

    override suspend fun correctionRate(householdId: Uuid): Double? = null

    override suspend fun void(id: Uuid): Receipt? = error("not used")


    override suspend fun reopen(id: Uuid): Receipt? = error("not used")
}

class FakeHouseholds(private val known: List<Household>) : HouseholdRepository {
    override suspend fun create(name: String, defaultCurrency: CurrencyCode): Household = error("not used")

    override suspend fun findById(id: Uuid): Household? = known.firstOrNull { it.id == id }

    override suspend fun rename(id: Uuid, name: String): Household? = error("not used")

    override suspend fun delete(id: Uuid): Boolean = error("not used")
}
