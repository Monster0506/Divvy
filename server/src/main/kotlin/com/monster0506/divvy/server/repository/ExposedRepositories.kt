package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.db.HouseholdTable
import com.monster0506.divvy.server.db.ItemOwnerHistoryTable
import com.monster0506.divvy.server.db.ItemOwnerTable
import com.monster0506.divvy.server.db.PersonTable
import com.monster0506.divvy.server.db.ReceiptItemTable
import com.monster0506.divvy.server.db.ReceiptTable
import com.monster0506.divvy.server.db.SettlementTable
import com.monster0506.divvy.server.db.dbQuery
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.ItemOwnerHistoryEntry
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipChange
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.domain.Settlement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.Uuid

/** Exposed-backed implementations of the repository interfaces, sharing one [Database]. */
class ExposedRepositories(db: Database) {
    val households: HouseholdRepository = ExposedHouseholdRepository(db)
    val people: PersonRepository = ExposedPersonRepository(db)
    val receipts: ReceiptRepository = ExposedReceiptRepository(db)
    val itemOwners: ItemOwnerRepository = ExposedItemOwnerRepository(db)
    val settlements: SettlementRepository = ExposedSettlementRepository(db)
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

    override suspend fun archive(id: Uuid): Person? = db.dbQuery {
        PersonTable.updateReturning(where = { (PersonTable.id eq id) and PersonTable.archivedAt.isNull() }) {
            it[PersonTable.archivedAt] = CurrentTimestampWithTimeZone
        }.singleOrNull()?.toPerson()
            ?: PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.toPerson()
    }
}

class ExposedReceiptRepository(private val db: Database) : ReceiptRepository {
    override suspend fun create(receipt: NewReceipt): Receipt = db.dbQuery {
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
        }.toList()
        loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun update(id: Uuid, patch: ReceiptPatch): Receipt? = db.dbQuery {
        lockDraft(id) ?: return@dbQuery null
        if (patch != ReceiptPatch()) {
            ReceiptTable.update({ ReceiptTable.id eq id }) {
                patch.paidByPersonId?.let { v -> it[paidByPersonId] = v }
                patch.merchantName?.let { v -> it[merchantName] = v }
                patch.purchasedAt?.let { v -> it[purchasedAt] = v.toUtc() }
                patch.taxMinor?.let { v -> it[taxMinor] = v }
                patch.totalMinor?.let { v -> it[totalMinor] = v }
            }
        }
        loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun addItem(receiptId: Uuid, item: NewReceiptItem): Receipt? = db.dbQuery {
        val householdId = lockDraft(receiptId) ?: return@dbQuery null
        val nextLine = (ReceiptItemTable.select(ReceiptItemTable.lineNumber)
            .where { ReceiptItemTable.receiptId eq receiptId }
            .maxOfOrNull { it[ReceiptItemTable.lineNumber] } ?: 0) + 1
        val itemId = ReceiptItemTable.insertReturning(listOf(ReceiptItemTable.id)) {
            it[ReceiptItemTable.householdId] = householdId
            it[ReceiptItemTable.receiptId] = receiptId
            it[lineNumber] = nextLine
            it[rawText] = item.rawText
            it[description] = item.description
            it[quantity] = item.quantity
            it[unitPriceMinor] = item.unitPriceMinor
            it[discountMinor] = item.discountMinor
            it[totalMinor] = item.totalMinor
        }.single()[ReceiptItemTable.id]
        item.owners.forEach { owner ->
            ItemOwnerTable.insert {
                it[ItemOwnerTable.householdId] = householdId
                it[receiptItemId] = itemId
                it[personId] = owner.personId
                it[shareWeight] = owner.shareWeight
                it[ownershipSource] = owner.source.dbValue
                it[confidence] = owner.confidence
            }
        }
        loadReceipts(listOf(receiptId)).single()
    }

    override suspend fun updateItem(receiptId: Uuid, itemId: Uuid, patch: ReceiptItemPatch): Receipt? = db.dbQuery {
        lockDraft(receiptId) ?: return@dbQuery null
        if (!itemExists(receiptId, itemId)) return@dbQuery null
        if (!patch.isEmpty) {
            ReceiptItemTable.update({ ReceiptItemTable.id eq itemId }) {
                patch.description?.let { v -> it[description] = v }
                patch.quantity?.let { v -> it[quantity] = v }
                patch.unitPriceMinor?.let { v -> it[unitPriceMinor] = v }
                patch.discountMinor?.let { v -> it[discountMinor] = v }
                patch.totalMinor?.let { v -> it[totalMinor] = v }
            }
        }
        loadReceipts(listOf(receiptId)).single()
    }

    override suspend fun deleteItem(receiptId: Uuid, itemId: Uuid): Receipt? = db.dbQuery {
        lockDraft(receiptId) ?: return@dbQuery null
        if (!itemExists(receiptId, itemId)) return@dbQuery null
        ReceiptItemTable.deleteWhere { ReceiptItemTable.id eq itemId }
        loadReceipts(listOf(receiptId)).single()
    }

    override suspend fun mergeItems(receiptId: Uuid, itemIds: List<Uuid>, description: String?): Receipt? = db.dbQuery {
        lockDraft(receiptId) ?: return@dbQuery null
        val rows = ReceiptItemTable.selectAll()
            .where { (ReceiptItemTable.receiptId eq receiptId) and (ReceiptItemTable.id inList itemIds) }
            .orderBy(ReceiptItemTable.lineNumber)
            .toList()
        if (rows.size != itemIds.distinct().size || rows.size < 2) return@dbQuery null
        val target = rows.first()
        ReceiptItemTable.update({ ReceiptItemTable.id eq target[ReceiptItemTable.id] }) {
            it[ReceiptItemTable.description] = description ?: target[ReceiptItemTable.description]
            it[quantity] = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row[ReceiptItemTable.quantity] }
            it[unitPriceMinor] = null
            it[discountMinor] = rows.sumOf { row -> row[ReceiptItemTable.discountMinor] }
            it[totalMinor] = rows.sumOf { row -> row[ReceiptItemTable.totalMinor] }
        }
        val others = rows.drop(1).map { it[ReceiptItemTable.id] }
        ReceiptItemTable.deleteWhere { ReceiptItemTable.id inList others }
        loadReceipts(listOf(receiptId)).single()
    }

    /** Locks a receipt row and returns its household, null if missing; throws if it is not a draft. */
    private fun lockDraft(id: Uuid): Uuid? {
        val row = ReceiptTable.selectAll().where { ReceiptTable.id eq id }.forUpdate().singleOrNull() ?: return null
        if (ReceiptStatus.fromDb(row[ReceiptTable.status]) != ReceiptStatus.DRAFT) {
            throw ReceiptLockedException("Receipt $id is ${row[ReceiptTable.status]} and can no longer be edited")
        }
        return row[ReceiptTable.householdId]
    }

    private fun itemExists(receiptId: Uuid, itemId: Uuid): Boolean =
        !ReceiptItemTable.select(ReceiptItemTable.id)
            .where { (ReceiptItemTable.id eq itemId) and (ReceiptItemTable.receiptId eq receiptId) }
            .empty()

    override suspend fun void(id: Uuid): Receipt? = db.dbQuery {
        ReceiptTable.updateReturning(returning = listOf(ReceiptTable.id), where = { ReceiptTable.id eq id }) {
            it[status] = ReceiptStatus.VOID.dbValue
        }.toList()
        loadReceipts(listOf(id)).singleOrNull()
    }

    /** Loads receipts with their items and owners in three queries, preserving the order of [ids]. */
    private fun loadReceipts(ids: List<Uuid>): List<Receipt> {
        if (ids.isEmpty()) return emptyList()
        val receiptRows = ReceiptTable.selectAll().where { ReceiptTable.id inList ids }.associateBy { it[ReceiptTable.id] }
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

// --- shared queries (must run inside a transaction) ------------------------------------------

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