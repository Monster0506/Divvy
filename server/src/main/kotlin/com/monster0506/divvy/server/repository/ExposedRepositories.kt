package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.db.HouseholdTable
import com.monster0506.divvy.server.db.ItemOwnerHistoryTable
import com.monster0506.divvy.server.db.ItemOwnerTable
import com.monster0506.divvy.server.db.LedgerEntryTable
import com.monster0506.divvy.server.db.PersonTable
import com.monster0506.divvy.server.db.ReceiptItemTable
import com.monster0506.divvy.server.db.ReceiptTable
import com.monster0506.divvy.server.db.SettlementTable
import com.monster0506.divvy.server.db.dbQuery
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.ItemOwnerHistoryEntry
import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipChange
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.domain.Settlement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

/** Exposed-backed implementations of the repository interfaces, sharing one [Database]. */
class ExposedRepositories(
    db: Database,
    /** Lets the app wrap receipts, for example to push confirmed labels to the ML service. */
    wrapReceipts: (ReceiptRepository) -> ReceiptRepository = { it },
) {
    val households: HouseholdRepository = ExposedHouseholdRepository(db)
    val people: PersonRepository = ExposedPersonRepository(db)
    val receipts: ReceiptRepository = wrapReceipts(ExposedReceiptRepository(db))
    val itemOwners: ItemOwnerRepository = ExposedItemOwnerRepository(db)
    val settlements: SettlementRepository = ExposedSettlementRepository(db)
    val ledger: LedgerRepository = ExposedLedgerRepository(db)
}

class ExposedHouseholdRepository(private val db: Database) : HouseholdRepository {
    override suspend fun create(name: String, defaultCurrency: CurrencyCode): Household = db.dbQuery {
        HouseholdTable.insertReturning {
            it[HouseholdTable.name] = name
            it[HouseholdTable.defaultCurrency] = defaultCurrency.code
        }.single().toHousehold()
    }

    override suspend fun findById(id: Uuid): Household? = db.dbQuery {
        HouseholdTable.selectAll().where { HouseholdTable.id eq id }.singleOrNull()?.toHousehold()
    }

    override suspend fun rename(id: Uuid, name: String): Household? = db.dbQuery {
        HouseholdTable.updateReturning(where = { HouseholdTable.id eq id }) {
            it[HouseholdTable.name] = name
        }.singleOrNull()?.toHousehold()
    }

    override suspend fun delete(id: Uuid): Boolean = db.dbQuery {
        // Person foreign keys are checked at commit so the cascade can remove receipts and items
        // before it removes the people they reference.
        exec("SET CONSTRAINTS ALL DEFERRED")
        HouseholdTable.deleteWhere { HouseholdTable.id eq id } > 0
    }
}

class ExposedPersonRepository(private val db: Database) : PersonRepository {
    override suspend fun add(householdId: Uuid, displayName: String): Person = db.dbQuery {
        PersonTable.insertReturning {
            it[PersonTable.householdId] = householdId
            it[PersonTable.displayName] = displayName
        }.single().toPerson()
    }

    override suspend fun findById(id: Uuid): Person? = db.dbQuery {
        PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.toPerson()
    }

    override suspend fun listByHousehold(householdId: Uuid, includeArchived: Boolean): List<Person> = db.dbQuery {
        peopleOf(householdId, includeArchived)
    }

    override suspend fun rename(id: Uuid, displayName: String): Person? = db.dbQuery {
        PersonTable.updateReturning(where = { PersonTable.id eq id }) {
            it[PersonTable.displayName] = displayName
        }.singleOrNull()?.toPerson()
    }

    override suspend fun archive(id: Uuid): Person? = db.dbQuery {
        PersonTable.updateReturning(where = { (PersonTable.id eq id) and PersonTable.archivedAt.isNull() }) {
            it[PersonTable.archivedAt] = CurrentTimestampWithTimeZone
        }.singleOrNull()?.toPerson()
            ?: PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.toPerson()
    }
}

class ExposedReceiptRepository(private val db: Database) : ReceiptRepository {
    override suspend fun create(receipt: NewReceipt): Receipt = db.dbQuery {
        rejectArchivedPeople(receipt.items.flatMap { item -> item.owners.map { it.personId } })
        val receiptId = ReceiptTable.insertReturning(listOf(ReceiptTable.id)) {
            it[householdId] = receipt.householdId
            it[paidByPersonId] = receipt.paidByPersonId
            it[merchantName] = receipt.merchantName
            it[purchasedAt] = receipt.purchasedAt.toUtc()
            it[currency] = receipt.currency.code
            it[subtotalMinor] = receipt.subtotalMinor
            it[taxMinor] = receipt.taxMinor
            it[totalMinor] = receipt.totalMinor
        }.single()[ReceiptTable.id]

        // Items and their owners are written in the same transaction as the receipt, so a bad
        // item or owner leaves nothing behind.
        receipt.items.forEachIndexed { index, item ->
            val itemId = ReceiptItemTable.insertReturning(listOf(ReceiptItemTable.id)) {
                it[householdId] = receipt.householdId
                it[ReceiptItemTable.receiptId] = receiptId
                it[lineNumber] = index + 1
                it[rawText] = item.rawText
                it[description] = item.description
                it[quantity] = item.quantity
                it[unitPriceMinor] = item.unitPriceMinor
                it[discountMinor] = item.discountMinor
                it[totalMinor] = item.totalMinor
            }.single()[ReceiptItemTable.id]

            item.owners.forEach { owner ->
                ItemOwnerTable.insert {
                    it[ItemOwnerTable.householdId] = receipt.householdId
                    it[receiptItemId] = itemId
                    it[personId] = owner.personId
                    it[shareWeight] = owner.shareWeight
                    it[ownershipSource] = owner.source.dbValue
                    it[confidence] = owner.confidence
                }
            }
        }
        loadReceipts(listOf(receiptId)).single()
    }

    override suspend fun findById(id: Uuid): Receipt? = db.dbQuery {
        loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun listByHousehold(householdId: Uuid, limit: Int): List<Receipt> = db.dbQuery {
        val ids = ReceiptTable.select(ReceiptTable.id)
            .where { ReceiptTable.householdId eq householdId }
            .orderBy(ReceiptTable.purchasedAt to SortOrder.DESC, ReceiptTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it[ReceiptTable.id] }
        loadReceipts(ids)
    }

    /** No effect unless the receipt is a draft. */
    override suspend fun confirm(id: Uuid): Receipt? = db.dbQuery {
        ReceiptTable.updateReturning(
            returning = listOf(ReceiptTable.id),
            where = { (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.DRAFT.dbValue) },
        ) {
            it[status] = ReceiptStatus.CONFIRMED.dbValue
            it[confirmedAt] = CurrentTimestampWithTimeZone
        }.toList().also { if (it.isNotEmpty()) recordLabelSources(id) }
        loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun confirmAssigned(id: Uuid): ConfirmResult = db.dbQuery {
        val receipt = loadReceipts(listOf(id)).singleOrNull() ?: return@dbQuery ConfirmResult.NotFound
        if (receipt.status != ReceiptStatus.DRAFT) return@dbQuery ConfirmResult.NotDraft(receipt.status)

        val archived = PersonTable.select(PersonTable.id)
            .where { (PersonTable.householdId eq receipt.householdId) and PersonTable.archivedAt.isNotNull() }
            .map { it[PersonTable.id] }
            .toSet()
        val unassigned = receipt.items.filter { item -> item.owners.none { it.personId !in archived } }
        if (unassigned.isNotEmpty()) return@dbQuery ConfirmResult.Unassigned(unassigned.map { it.id })

        ItemOwnerTable.update({
            (ItemOwnerTable.receiptItemId inList receipt.items.map { it.id }) and
                (ItemOwnerTable.ownershipSource eq OwnershipSource.MODEL.dbValue)
        }) {
            it[ownershipSource] = OwnershipSource.USER.dbValue
        }
        ReceiptTable.update({ (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.DRAFT.dbValue) }) {
            it[status] = ReceiptStatus.CONFIRMED.dbValue
            it[confirmedAt] = CurrentTimestampWithTimeZone
        }
        ConfirmResult.Confirmed(loadReceipts(listOf(id)).single())
    }

    override suspend fun correctionRate(householdId: Uuid): Double? = db.dbQuery {
        val predictedSources = listOf(LabelSource.PREDICTED_ACCEPTED.dbValue, LabelSource.PREDICTED_CORRECTED.dbValue)
        val counts = ReceiptItemTable
            .select(ReceiptItemTable.labelSource, ReceiptItemTable.id.count())
            .where { (ReceiptItemTable.householdId eq householdId) and (ReceiptItemTable.labelSource inList predictedSources) }
            .groupBy(ReceiptItemTable.labelSource)
            .associate { it[ReceiptItemTable.labelSource] to it[ReceiptItemTable.id.count()] }
        val corrected = counts[LabelSource.PREDICTED_CORRECTED.dbValue] ?: 0L
        val total = corrected + (counts[LabelSource.PREDICTED_ACCEPTED.dbValue] ?: 0L)
        if (total == 0L) null else corrected.toDouble() / total
    }

    /** Compares each item's final owners with the model's first prediction for it. */
    private fun recordLabelSources(receiptId: Uuid) {
        val items = ReceiptItemTable.select(ReceiptItemTable.id)
            .where { ReceiptItemTable.receiptId eq receiptId }
            .map { it[ReceiptItemTable.id] }
        if (items.isEmpty()) return
        val finalOwners = ownersOf(items).groupBy({ it.receiptItemId }) { it.personId to it.shareWeight }
        val predicted = ItemOwnerHistoryTable.selectAll()
            .where {
                (ItemOwnerHistoryTable.receiptItemId inList items) and
                        (ItemOwnerHistoryTable.operation eq OwnershipChange.ASSIGNED.dbValue) and
                        (ItemOwnerHistoryTable.ownershipSource eq OwnershipSource.MODEL.dbValue)
            }
            .orderBy(ItemOwnerHistoryTable.id)
            .toList()
            .groupBy({ it[ItemOwnerHistoryTable.receiptItemId] }) {
                it[ItemOwnerHistoryTable.personId] to it[ItemOwnerHistoryTable.shareWeight]
            }
        for (itemId in items) {
            val guess = predicted[itemId]?.toMap()
            val source = when {
                guess == null -> LabelSource.MANUAL
                guess == finalOwners[itemId].orEmpty().toMap() -> LabelSource.PREDICTED_ACCEPTED
                else -> LabelSource.PREDICTED_CORRECTED
            }
            ReceiptItemTable.update({ ReceiptItemTable.id eq itemId }) { it[labelSource] = source.dbValue }
        }
    }

    override suspend fun void(id: Uuid): Receipt? = db.dbQuery {
        ReceiptTable.updateReturning(returning = listOf(ReceiptTable.id), where = { ReceiptTable.id eq id }) {
            it[status] = ReceiptStatus.VOID.dbValue
        }.toList()
        loadReceipts(listOf(id)).singleOrNull()
    }

    /** Loads receipts with their items and owners in three queries, preserving the order of [ids]. */
    private fun loadReceipts(ids: List<Uuid>): List<Receipt> {
        if (ids.isEmpty()) return emptyList()
        val receiptRows =
            ReceiptTable.selectAll().where { ReceiptTable.id inList ids }.associateBy { it[ReceiptTable.id] }
        val itemRows = ReceiptItemTable.selectAll()
            .where { ReceiptItemTable.receiptId inList ids }
            .orderBy(ReceiptItemTable.lineNumber)
            .toList()
        val ownersByItem = ownersOf(itemRows.map { it[ReceiptItemTable.id] }).groupBy { it.receiptItemId }
        val currencyByReceipt = receiptRows.mapValues { CurrencyCode(it.value[ReceiptTable.currency]) }
        val itemsByReceipt = itemRows.groupBy({ it[ReceiptItemTable.receiptId] }) { row ->
            row.toReceiptItem(
                currencyByReceipt.getValue(row[ReceiptItemTable.receiptId]),
                ownersByItem[row[ReceiptItemTable.id]].orEmpty(),
            )
        }
        return ids.mapNotNull { id -> receiptRows[id]?.toReceipt(itemsByReceipt[id].orEmpty()) }
    }
}

class ExposedItemOwnerRepository(private val db: Database) : ItemOwnerRepository {
    override suspend fun setOwners(receiptItemId: Uuid, owners: List<OwnerShare>): List<ItemOwner> = db.dbQuery {
        replaceOwners(receiptItemId, owners)
    }

    override suspend fun assignToAllMembers(receiptItemId: Uuid, source: OwnershipSource): List<ItemOwner> =
        db.dbQuery {
            val householdId = householdOfItem(receiptItemId)
            val everyone = peopleOf(householdId, includeArchived = false)
                .map { OwnerShare(personId = it.id, source = source) }
            replaceOwners(receiptItemId, everyone)
        }

    override suspend fun ownersOf(receiptItemId: Uuid): List<ItemOwner> = db.dbQuery {
        ownersOf(listOf(receiptItemId))
    }

    override suspend fun history(receiptItemId: Uuid): List<ItemOwnerHistoryEntry> = db.dbQuery {
        ItemOwnerHistoryTable.selectAll()
            .where { ItemOwnerHistoryTable.receiptItemId eq receiptItemId }
            .orderBy(ItemOwnerHistoryTable.id)
            .map { it.toHistoryEntry() }
    }

    private fun replaceOwners(receiptItemId: Uuid, owners: List<OwnerShare>): List<ItemOwner> {
        require(owners.map { it.personId }.toSet().size == owners.size) { "Each person may own an item only once" }
        val householdId = householdOfItem(receiptItemId)
        val keep = owners.map { it.personId }
        val existing = ItemOwnerTable.select(ItemOwnerTable.personId)
            .where { ItemOwnerTable.receiptItemId eq receiptItemId }
            .map { it[ItemOwnerTable.personId] }
            .toSet()
        rejectArchivedPeople(keep, alreadyOwners = existing)
        ItemOwnerTable.deleteWhere {
            (ItemOwnerTable.receiptItemId eq receiptItemId) and (ItemOwnerTable.personId notInList keep)
        }
        owners.forEach { owner ->
            ItemOwnerTable.upsert(
                ItemOwnerTable.receiptItemId,
                ItemOwnerTable.personId,
                onUpdateExclude = listOf(ItemOwnerTable.householdId),
            ) {
                it[ItemOwnerTable.householdId] = householdId
                it[ItemOwnerTable.receiptItemId] = receiptItemId
                it[personId] = owner.personId
                it[shareWeight] = owner.shareWeight
                it[ownershipSource] = owner.source.dbValue
                it[confidence] = owner.confidence
            }
        }
        return ownersOf(listOf(receiptItemId))
    }

    private fun householdOfItem(receiptItemId: Uuid): Uuid =
        ReceiptItemTable.select(ReceiptItemTable.householdId)
            .where { ReceiptItemTable.id eq receiptItemId }
            .singleOrNull()
            ?.get(ReceiptItemTable.householdId)
            ?: throw NoSuchElementException("Receipt item $receiptItemId does not exist")
}

class ExposedSettlementRepository(private val db: Database) : SettlementRepository {
    override suspend fun record(settlement: NewSettlement): Settlement = db.dbQuery {
        SettlementTable.insertReturning {
            it[householdId] = settlement.householdId
            it[fromPersonId] = settlement.fromPersonId
            it[toPersonId] = settlement.toPersonId
            it[amountMinor] = settlement.amount.amountMinor
            it[currency] = settlement.amount.currency.code
            settlement.settledAt?.let { at -> it[settledAt] = at.toUtc() }
            it[note] = settlement.note
        }.single().toSettlement()
    }

    override suspend fun findById(id: Uuid): Settlement? = db.dbQuery {
        SettlementTable.selectAll().where { SettlementTable.id eq id }.singleOrNull()?.toSettlement()
    }

    override suspend fun listByHousehold(householdId: Uuid): List<Settlement> = db.dbQuery {
        SettlementTable.selectAll()
            .where { SettlementTable.householdId eq householdId }
            .orderBy(SettlementTable.settledAt to SortOrder.DESC, SettlementTable.createdAt to SortOrder.DESC)
            .map { it.toSettlement() }
    }
}

class ExposedLedgerRepository(private val db: Database) : LedgerRepository {
    override suspend fun append(entry: NewLedgerEntry): LedgerEntry = db.dbQuery { insertEntry(entry, null) }

    override suspend fun reverse(entryId: Uuid): LedgerEntry? = db.dbQuery {
        val original = LedgerEntryTable.selectAll().where { LedgerEntryTable.id eq entryId }
            .singleOrNull()?.toLedgerEntry() ?: return@dbQuery null
        insertEntry(
            NewLedgerEntry(
                householdId = original.householdId,
                debtorPersonId = original.creditorPersonId,
                creditorPersonId = original.debtorPersonId,
                amount = original.amount,
                sourceType = original.sourceType,
                sourceId = original.sourceId,
            ),
            reverses = original.id,
        )
    }

    override suspend fun findById(id: Uuid): LedgerEntry? = db.dbQuery {
        LedgerEntryTable.selectAll().where { LedgerEntryTable.id eq id }.singleOrNull()?.toLedgerEntry()
    }

    override suspend fun listByHousehold(householdId: Uuid): List<LedgerEntry> = db.dbQuery {
        LedgerEntryTable.selectAll()
            .where { LedgerEntryTable.householdId eq householdId }
            .orderBy(LedgerEntryTable.createdAt to SortOrder.ASC, LedgerEntryTable.id to SortOrder.ASC)
            .map { it.toLedgerEntry() }
    }

    override suspend fun listBySource(sourceType: LedgerSourceType, sourceId: Uuid): List<LedgerEntry> = db.dbQuery {
        LedgerEntryTable.selectAll()
            .where { (LedgerEntryTable.sourceType eq sourceType.dbValue) and (LedgerEntryTable.sourceId eq sourceId) }
            .orderBy(LedgerEntryTable.createdAt to SortOrder.ASC, LedgerEntryTable.id to SortOrder.ASC)
            .map { it.toLedgerEntry() }
    }

    private fun insertEntry(entry: NewLedgerEntry, reverses: Uuid?): LedgerEntry =
        LedgerEntryTable.insertReturning {
            it[householdId] = entry.householdId
            it[debtorPersonId] = entry.debtorPersonId
            it[creditorPersonId] = entry.creditorPersonId
            it[amountMinor] = entry.amount.amountMinor
            it[currency] = entry.amount.currency.code
            it[sourceType] = entry.sourceType.dbValue
            it[sourceId] = entry.sourceId
            it[reversesEntryId] = reverses
        }.single().toLedgerEntry()
}

// --- shared queries (must run inside a transaction) ------------------------------------------

/** Rejects archived people, except those in [alreadyOwners] who keep their existing ownership. */
private fun rejectArchivedPeople(personIds: Collection<Uuid>, alreadyOwners: Set<Uuid> = emptySet()) {
    val candidates = personIds.toSet() - alreadyOwners
    if (candidates.isEmpty()) return
    val archived = PersonTable.select(PersonTable.id)
        .where { (PersonTable.id inList candidates) and PersonTable.archivedAt.isNotNull() }
        .map { it[PersonTable.id] }
    if (archived.isNotEmpty()) throw ArchivedPersonException(archived)
}

private fun peopleOf(householdId: Uuid, includeArchived: Boolean): List<Person> =
    PersonTable.selectAll()
        .where { PersonTable.householdId eq householdId }
        .apply { if (!includeArchived) andWhere { PersonTable.archivedAt.isNull() } }
        .orderBy(PersonTable.createdAt to SortOrder.ASC, PersonTable.id to SortOrder.ASC)
        .map { it.toPerson() }

private fun ownersOf(itemIds: List<Uuid>): List<ItemOwner> {
    if (itemIds.isEmpty()) return emptyList()
    return ItemOwnerTable.selectAll()
        .where { ItemOwnerTable.receiptItemId inList itemIds }
        .orderBy(ItemOwnerTable.createdAt to SortOrder.ASC, ItemOwnerTable.personId to SortOrder.ASC)
        .map { it.toItemOwner() }
}

// --- row mapping ------------------------------------------------------------------------------

private fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

private fun ResultRow.toHousehold() = Household(
    id = this[HouseholdTable.id],
    name = this[HouseholdTable.name],
    defaultCurrency = CurrencyCode(this[HouseholdTable.defaultCurrency]),
    createdAt = this[HouseholdTable.createdAt].toInstant(),
    updatedAt = this[HouseholdTable.updatedAt].toInstant(),
)

private fun ResultRow.toPerson() = Person(
    id = this[PersonTable.id],
    householdId = this[PersonTable.householdId],
    displayName = this[PersonTable.displayName],
    archivedAt = this[PersonTable.archivedAt]?.toInstant(),
    createdAt = this[PersonTable.createdAt].toInstant(),
    updatedAt = this[PersonTable.updatedAt].toInstant(),
)

private fun ResultRow.toReceipt(items: List<ReceiptItem>): Receipt {
    val currency = CurrencyCode(this[ReceiptTable.currency])
    return Receipt(
        id = this[ReceiptTable.id],
        householdId = this[ReceiptTable.householdId],
        paidByPersonId = this[ReceiptTable.paidByPersonId],
        merchantName = this[ReceiptTable.merchantName],
        purchasedAt = this[ReceiptTable.purchasedAt].toInstant(),
        subtotal = this[ReceiptTable.subtotalMinor]?.let { Money(it, currency) },
        tax = this[ReceiptTable.taxMinor]?.let { Money(it, currency) },
        total = Money(this[ReceiptTable.totalMinor], currency),
        status = ReceiptStatus.fromDb(this[ReceiptTable.status]),
        confirmedAt = this[ReceiptTable.confirmedAt]?.toInstant(),
        items = items,
        createdAt = this[ReceiptTable.createdAt].toInstant(),
        updatedAt = this[ReceiptTable.updatedAt].toInstant(),
    )
}

private fun ResultRow.toReceiptItem(currency: CurrencyCode, owners: List<ItemOwner>) = ReceiptItem(
    id = this[ReceiptItemTable.id],
    receiptId = this[ReceiptItemTable.receiptId],
    lineNumber = this[ReceiptItemTable.lineNumber],
    rawText = this[ReceiptItemTable.rawText],
    description = this[ReceiptItemTable.description],
    quantity = this[ReceiptItemTable.quantity],
    unitPrice = this[ReceiptItemTable.unitPriceMinor]?.let { Money(it, currency) },
    discount = Money(this[ReceiptItemTable.discountMinor], currency),
    total = Money(this[ReceiptItemTable.totalMinor], currency),
    owners = owners,
    labelSource = this[ReceiptItemTable.labelSource]?.let(LabelSource::fromDb),
    createdAt = this[ReceiptItemTable.createdAt].toInstant(),
    updatedAt = this[ReceiptItemTable.updatedAt].toInstant(),
)

private fun ResultRow.toItemOwner() = ItemOwner(
    receiptItemId = this[ItemOwnerTable.receiptItemId],
    personId = this[ItemOwnerTable.personId],
    shareWeight = this[ItemOwnerTable.shareWeight],
    source = OwnershipSource.fromDb(this[ItemOwnerTable.ownershipSource]),
    confidence = this[ItemOwnerTable.confidence],
    createdAt = this[ItemOwnerTable.createdAt].toInstant(),
    updatedAt = this[ItemOwnerTable.updatedAt].toInstant(),
)

private fun ResultRow.toHistoryEntry() = ItemOwnerHistoryEntry(
    id = this[ItemOwnerHistoryTable.id],
    receiptItemId = this[ItemOwnerHistoryTable.receiptItemId],
    personId = this[ItemOwnerHistoryTable.personId],
    change = OwnershipChange.fromDb(this[ItemOwnerHistoryTable.operation]),
    shareWeight = this[ItemOwnerHistoryTable.shareWeight],
    source = OwnershipSource.fromDb(this[ItemOwnerHistoryTable.ownershipSource]),
    confidence = this[ItemOwnerHistoryTable.confidence],
    changedAt = this[ItemOwnerHistoryTable.changedAt].toInstant(),
)

private fun ResultRow.toLedgerEntry() = LedgerEntry(
    id = this[LedgerEntryTable.id],
    householdId = this[LedgerEntryTable.householdId],
    debtorPersonId = this[LedgerEntryTable.debtorPersonId],
    creditorPersonId = this[LedgerEntryTable.creditorPersonId],
    amount = Money(this[LedgerEntryTable.amountMinor], CurrencyCode(this[LedgerEntryTable.currency])),
    sourceType = LedgerSourceType.fromDb(this[LedgerEntryTable.sourceType]),
    sourceId = this[LedgerEntryTable.sourceId],
    reversesEntryId = this[LedgerEntryTable.reversesEntryId],
    createdAt = this[LedgerEntryTable.createdAt].toInstant(),
)

private fun ResultRow.toSettlement() = Settlement(
    id = this[SettlementTable.id],
    householdId = this[SettlementTable.householdId],
    fromPersonId = this[SettlementTable.fromPersonId],
    toPersonId = this[SettlementTable.toPersonId],
    amount = Money(this[SettlementTable.amountMinor], CurrencyCode(this[SettlementTable.currency])),
    settledAt = this[SettlementTable.settledAt].toInstant(),
    note = this[SettlementTable.note],
    createdAt = this[SettlementTable.createdAt].toInstant(),
    updatedAt = this[SettlementTable.updatedAt].toInstant(),
)