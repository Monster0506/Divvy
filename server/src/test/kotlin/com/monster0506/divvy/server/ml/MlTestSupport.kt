package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.PersonRepository
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

val usd = CurrencyCode("USD")
val t0: Instant = Instant.parse("2026-09-01T12:00:00Z")

fun person(household: Uuid, name: String, created: Instant = t0, archived: Instant? = null) =
    Person(Uuid.random(), household, name, archived, created, created)

fun item(
    receipt: Uuid,
    description: String,
    owners: List<Person>,
    source: LabelSource? = LabelSource.MANUAL,
    line: Int = 1,
) : ReceiptItem {
    val id = Uuid.random()
    return ReceiptItem(
        id = id,
        receiptId = receipt,
        lineNumber = line,
        rawText = null,
        description = description,
        quantity = BigDecimal.ONE,
        unitPrice = null,
        discount = Money(0, usd),
        total = Money(100, usd),
        owners = owners.map { ItemOwner(id, it.id, 1, OwnershipSource.USER, null, t0, t0) },
        labelSource = source,
        createdAt = t0,
        updatedAt = t0,
    )
}

fun receipt(
    household: Uuid,
    status: ReceiptStatus = ReceiptStatus.CONFIRMED,
    confirmedAt: Instant? = t0.plusSeconds(3600),
    purchasedAt: Instant = Instant.parse("2026-09-30T23:30:00Z"),
    id: Uuid = Uuid.random(),
    items: (Uuid) -> List<ReceiptItem>,
) = Receipt(
    id = id,
    householdId = household,
    paidByPersonId = Uuid.random(),
    merchantName = "Trader Joe's",
    purchasedAt = purchasedAt,
    subtotal = null,
    tax = null,
    total = Money(100, usd),
    status = status,
    confirmedAt = confirmedAt,
    items = items(id),
    createdAt = t0,
    updatedAt = t0,
)

class FakePeople(private val everyone: List<Person>) : PersonRepository {
    override suspend fun add(householdId: Uuid, displayName: String) = error("not used")

    override suspend fun findById(id: Uuid) = everyone.firstOrNull { it.id == id }

    override suspend fun listByHousehold(householdId: Uuid, includeArchived: Boolean) =
        everyone.filter { it.householdId == householdId && (includeArchived || it.archivedAt == null) }

    override suspend fun rename(id: Uuid, displayName: String) = error("not used")

    override suspend fun archive(id: Uuid) = error("not used")
}

/** Records every call and answers with a configurable result. */
class FakeMl : MlClient {
    val upserts = CopyOnWriteArrayList<Pair<String, List<MlObservation>>>()
    val deletes = CopyOnWriteArrayList<Pair<String, String>>()
    val events = CopyOnWriteArrayList<String>()

    var upsertResult: () -> MlResult<MlUpsertResponse> = { MlResult.Success(MlUpsertResponse("h", 0, 0)) }
    var deleteResult: (String) -> MlResult<Unit> = { MlResult.Success(Unit) }
    var historyResult: (String) -> MlResult<MlHistoryStatus> =
        { MlResult.Success(MlHistoryStatus(it, 0, 30, false)) }
    var upsertDelayMillis = 0L

    override suspend fun predict(request: MlPredictRequest): MlResult<MlPredictResponse> = error("not used")

    override suspend fun upsertObservations(
        householdId: String,
        observations: List<MlObservation>,
        replace: Boolean,
    ): MlResult<MlUpsertResponse> {
        events += "start $householdId"
        if (upsertDelayMillis > 0) delay(upsertDelayMillis)
        upserts += householdId to observations
        events += "end $householdId"
        return upsertResult()
    }

    override suspend fun deleteObservation(householdId: String, observationId: String): MlResult<Unit> {
        deletes += householdId to observationId
        return deleteResult(observationId)
    }

    override suspend fun history(householdId: String) = historyResult(householdId)
}
