package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlin.uuid.Uuid

/** Serves a fixed list of receipts; only listing is used by the resync. */
class FakeReceipts(var all: List<Receipt>) : ReceiptRepository {
    override suspend fun create(receipt: NewReceipt): Receipt = error("not used")

    override suspend fun findById(id: Uuid): Receipt? = all.firstOrNull { it.id == id }

    override suspend fun listByHousehold(householdId: Uuid, limit: Int): List<Receipt> =
        all.filter { it.householdId == householdId }.take(limit)

    override suspend fun update(id: Uuid, patch: ReceiptPatch): Receipt? = error("not used")

    override suspend fun addItem(receiptId: Uuid, item: NewReceiptItem): Receipt? = error("not used")

    override suspend fun updateItem(receiptId: Uuid, itemId: Uuid, patch: ReceiptItemPatch): Receipt? = error("not used")

    override suspend fun deleteItem(receiptId: Uuid, itemId: Uuid): Receipt? = error("not used")

    override suspend fun mergeItems(receiptId: Uuid, itemIds: List<Uuid>, description: String?): Receipt? = error("not used")

    override suspend fun confirm(id: Uuid): Receipt? = error("not used")

    override suspend fun correctionRate(householdId: Uuid): Double? = null

    override suspend fun void(id: Uuid): Receipt? = error("not used")
}

class FakeHouseholds(private val known: List<Household>) : HouseholdRepository {
    override suspend fun create(name: String, defaultCurrency: CurrencyCode): Household = error("not used")

    override suspend fun findById(id: Uuid): Household? = known.firstOrNull { it.id == id }

    override suspend fun rename(id: Uuid, name: String): Household? = error("not used")

    override suspend fun delete(id: Uuid): Boolean = error("not used")
}
