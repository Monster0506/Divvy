package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.ItemOwnerHistoryEntry
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.repository.ItemOwnerRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

/** Records every owner change the service makes. */
class FakeItemOwners : ItemOwnerRepository {
    val calls = CopyOnWriteArrayList<Pair<Uuid, List<OwnerShare>>>()

    override suspend fun setOwners(receiptItemId: Uuid, owners: List<OwnerShare>): List<ItemOwner> {
        calls += receiptItemId to owners
        return owners.map {
            ItemOwner(receiptItemId, it.personId, it.shareWeight, it.source, it.confidence, t0, t0)
        }
    }

    override suspend fun assignToAllMembers(receiptItemId: Uuid, source: OwnershipSource): List<ItemOwner> =
        error("not used")

    override suspend fun ownersOf(receiptItemId: Uuid): List<ItemOwner> = error("not used")

    override suspend fun history(receiptItemId: Uuid): List<ItemOwnerHistoryEntry> = error("not used")
}

class FakePredictions : PredictionRepository {
    val saved = CopyOnWriteArrayList<StoredPrediction>()

    override suspend fun save(predictions: List<StoredPrediction>) {
        saved += predictions
    }

    override suspend fun forReceipt(receiptId: Uuid): List<StoredPrediction> = saved.toList()

    override suspend fun countForHousehold(householdId: Uuid): Int = saved.size
}
