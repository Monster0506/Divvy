package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.ledger.ledgerEntriesFor
import com.monster0506.divvy.server.db.HouseholdTable
import com.monster0506.divvy.server.db.ItemOwnerHistoryTable
import com.monster0506.divvy.server.db.ItemOwnerTable
import com.monster0506.divvy.server.db.LedgerEntryTable
import com.monster0506.divvy.server.db.ListItemTable
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
import com.monster0506.divvy.server.domain.ProcessedReceipt
import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.ListItem
import com.monster0506.divvy.server.domain.ListItemPatch
import com.monster0506.divvy.server.domain.ListItemStatus
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.domain.NewListItem
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipChange
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.PersonPatch
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptCursor
import com.monster0506.divvy.server.domain.ReceiptFilter
import com.monster0506.divvy.server.domain.ReceiptPage
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.domain.StoreSearch
import com.monster0506.divvy.server.domain.Settlement
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
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
    private val exposedReceipts = ExposedReceiptRepository(db)
    val households: HouseholdRepository = ExposedHouseholdRepository(db)
    val people: PersonRepository = ExposedPersonRepository(db)
    val receipts: ReceiptRepository = wrapReceipts(exposedReceipts)
    val itemOwners: ItemOwnerRepository = ExposedItemOwnerRepository(db)
    val settlements: SettlementRepository = ExposedSettlementRepository(db)
    val ledger: LedgerRepository = ExposedLedgerRepository(db)
    val listItems: ListItemRepository = ExposedListItemRepository(db)

    /**
     * Gives confirmed receipts the balances and item labels they should have had. Receipts confirmed through the app
     * before the confirm route wrote them have neither. Safe to run on every start: it only touches receipts that lack them.
     */
    suspend fun repairConfirmedReceipts(): Int = exposedReceipts.repairConfirmed()
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

    override suspend fun findByName(name: String): Household? = db.dbQuery {
        HouseholdTable.selectAll().where { HouseholdTable.name.lowerCase() eq name.lowercase() }
            .firstOrNull()?.toHousehold()
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

    override suspend fun update(id: Uuid, patch: PersonPatch): Person? = db.dbQuery {
        if (patch.isEmpty) return@dbQuery PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.toPerson()
        PersonTable.updateReturning(where = { PersonTable.id eq id }) {
            patch.displayName?.let { name -> it[PersonTable.displayName] = name }
            patch.venmoUsername?.let { username -> it[PersonTable.venmoUsername] = username.ifEmpty { null } }
            patch.shortName?.let { shortName -> it[PersonTable.shortName] = shortName.ifEmpty { null } }
            patch.emoji?.let { emoji -> it[PersonTable.emoji] = emoji.ifEmpty { null } }
            patch.color?.let { color -> it[PersonTable.avatarColor] = color.ifEmpty { null } }
        }.singleOrNull()?.toPerson()
    }

    override suspend fun archive(id: Uuid): Person? = db.dbQuery {
        PersonTable.updateReturning(where = { (PersonTable.id eq id) and PersonTable.archivedAt.isNull() }) {
            it[PersonTable.archivedAt] = CurrentTimestampWithTimeZone
        }.singleOrNull()?.toPerson()
            ?: PersonTable.selectAll().where { PersonTable.id eq id }.singleOrNull()?.toPerson()
    }

    override suspend fun setPhoto(id: Uuid, key: String?): Person? = db.dbQuery {
        PersonTable.updateReturning(where = { PersonTable.id eq id }) {
            it[PersonTable.photoKey] = key
            if (key == null) {
                it[PersonTable.photoUpdatedAt] = null
            } else {
                it[PersonTable.photoUpdatedAt] = CurrentTimestampWithTimeZone
            }
        }.singleOrNull()?.toPerson()
    }
}

private const val MAX_FAILURE_REASON = 300

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

    override suspend fun createProcessing(householdId: Uuid, imageKey: String): Receipt = db.dbQuery {
        val currency = HouseholdTable.select(HouseholdTable.defaultCurrency)
            .where { HouseholdTable.id eq householdId }
            .single()[HouseholdTable.defaultCurrency]
        val receiptId = ReceiptTable.insertReturning(listOf(ReceiptTable.id)) {
            it[ReceiptTable.householdId] = householdId
            it[ReceiptTable.currency] = currency
            it[ReceiptTable.imageKey] = imageKey
            it[status] = ReceiptStatus.PROCESSING.dbValue
        }.single()[ReceiptTable.id]
        loadReceipts(listOf(receiptId)).single()
    }

    override suspend fun completeProcessing(id: Uuid, processed: ProcessedReceipt): Receipt? = db.dbQuery {
        val row = ReceiptTable.selectAll()
            .where { (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.PROCESSING.dbValue) }
            .forUpdate()
            .singleOrNull() ?: return@dbQuery null
        val householdId = row[ReceiptTable.householdId]
        processed.items.forEachIndexed { index, item ->
            ReceiptItemTable.insert {
                it[ReceiptItemTable.householdId] = householdId
                it[receiptId] = id
                it[lineNumber] = index + 1
                it[rawText] = item.rawText
                it[description] = item.description
                it[quantity] = item.quantity
                it[unitPriceMinor] = item.unitPriceMinor
                it[discountMinor] = item.discountMinor
                it[totalMinor] = item.totalMinor
            }
        }
        ReceiptTable.update({ ReceiptTable.id eq id }) {
            it[merchantName] = processed.merchantName
            processed.purchasedAt?.let { at -> it[purchasedAt] = at.toUtc() }
            it[subtotalMinor] = processed.subtotalMinor
            it[taxMinor] = processed.taxMinor
            it[totalMinor] = processed.totalMinor
            it[status] = ReceiptStatus.DRAFT.dbValue
            it[failureReason] = null
        }
        loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun failProcessing(id: Uuid, reason: String): Receipt? = db.dbQuery {
        val changed = ReceiptTable.update({
            (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.PROCESSING.dbValue)
        }) {
            it[status] = ReceiptStatus.FAILED.dbValue
            it[failureReason] = reason.take(MAX_FAILURE_REASON)
        }
        if (changed == 0) null else loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun retryProcessing(id: Uuid): Receipt? = db.dbQuery {
        val changed = ReceiptTable.update({
            (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.FAILED.dbValue)
        }) {
            it[status] = ReceiptStatus.PROCESSING.dbValue
            it[failureReason] = null
        }
        if (changed == 0) null else loadReceipts(listOf(id)).singleOrNull()
    }

    override suspend fun idsWithStatus(status: ReceiptStatus): List<Uuid> = db.dbQuery {
        ReceiptTable.select(ReceiptTable.id)
            .where { ReceiptTable.status eq status.dbValue }
            .orderBy(ReceiptTable.createdAt)
            .map { it[ReceiptTable.id] }
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

    override suspend fun page(
        householdId: Uuid,
        filter: ReceiptFilter,
        after: ReceiptCursor?,
        limit: Int,
    ): ReceiptPage = db.dbQuery {
        val query = ReceiptTable.select(ReceiptTable.id, ReceiptTable.purchasedAt)
            .where { ReceiptTable.householdId eq householdId }
        filter.status?.let { s -> query.andWhere { ReceiptTable.status eq s.dbValue } }
        filter.merchant?.let { typed ->
            // Store names are few per household, so they are matched here, where typos and `*` are simple to handle.
            val search = StoreSearch(typed)
            val names = ReceiptTable.select(ReceiptTable.merchantName).withDistinct()
                .where { (ReceiptTable.householdId eq householdId) and ReceiptTable.merchantName.isNotNull() }
                .mapNotNull { it[ReceiptTable.merchantName] }
                .filter(search::matches)
            query.andWhere { ReceiptTable.merchantName inList names }
        }
        filter.from?.let { f -> query.andWhere { ReceiptTable.purchasedAt greaterEq f.toUtc() } }
        filter.to?.let { t -> query.andWhere { ReceiptTable.purchasedAt less t.toUtc() } }
        filter.paidByPersonId?.let { p -> query.andWhere { ReceiptTable.paidByPersonId eq p } }
        after?.let { c ->
            val at = c.purchasedAt.toUtc()
            query.andWhere {
                (ReceiptTable.purchasedAt less at) or
                    ((ReceiptTable.purchasedAt eq at) and (ReceiptTable.id less c.id))
            }
        }
        // One extra row tells us whether another page follows.
        val rows = query
            .orderBy(ReceiptTable.purchasedAt to SortOrder.DESC, ReceiptTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val pageRows = rows.take(limit)
        val next = if (rows.size > limit) {
            pageRows.last().let { ReceiptCursor(it[ReceiptTable.purchasedAt].toInstant(), it[ReceiptTable.id]) }
        } else {
            null
        }
        ReceiptPage(loadReceipts(pageRows.map { it[ReceiptTable.id] }), next)
    }

    /**
     * No effect unless the receipt is a draft. The ledger entries are written in the same
     * transaction as the status change, so both happen or neither does.
     */
    override suspend fun confirm(id: Uuid): Receipt? = db.dbQuery {
        val confirmed = ReceiptTable.updateReturning(
            returning = listOf(ReceiptTable.id),
            where = { (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.DRAFT.dbValue) },
        ) {
            it[status] = ReceiptStatus.CONFIRMED.dbValue
            it[confirmedAt] = CurrentTimestampWithTimeZone
        }.toList().isNotEmpty()
        if (confirmed) recordLabelSources(id)
        val receipt = loadReceipts(listOf(id)).singleOrNull()
        if (confirmed && receipt != null) writeReceiptLedger(receipt)
        receipt
    }

    /** Returns how many confirmed receipts needed balances or labels and got them. */
    suspend fun repairConfirmed(): Int = db.dbQuery {
        val withEntries = LedgerEntryTable.select(LedgerEntryTable.sourceId)
            .where { LedgerEntryTable.sourceType eq LedgerSourceType.RECEIPT.dbValue }
            .map { it[LedgerEntryTable.sourceId] }
            .toSet()
        val confirmed = ReceiptTable.select(ReceiptTable.id)
            .where { ReceiptTable.status eq ReceiptStatus.CONFIRMED.dbValue }
            .map { it[ReceiptTable.id] }
        var repaired = 0
        for (id in confirmed) {
            val receipt = loadReceipts(listOf(id)).singleOrNull() ?: continue
            val unlabelled = receipt.items.any { it.labelSource == null }
            val unbalanced = id !in withEntries && ledgerEntriesFor(receipt).isNotEmpty()
            if (!unlabelled && !unbalanced) continue
            if (unlabelled) recordLabelSources(id)
            if (unbalanced) writeReceiptLedger(receipt)
            repaired++
        }
        repaired
    }

    /**
     * Moves a confirmed receipt back to draft so it can be corrected, and cancels its ledger
     * entries. No effect unless the receipt is confirmed.
     */
    override suspend fun reopen(id: Uuid): Receipt? = db.dbQuery {
        val reopened = ReceiptTable.updateReturning(
            returning = listOf(ReceiptTable.id),
            where = { (ReceiptTable.id eq id) and (ReceiptTable.status eq ReceiptStatus.CONFIRMED.dbValue) },
        ) {
            it[status] = ReceiptStatus.DRAFT.dbValue
            it[confirmedAt] = null
        }.toList().isNotEmpty()
        if (reopened) reverseReceiptLedger(id)
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
        if (receipt.paidByPersonId == null) return@dbQuery ConfirmResult.NoPayer

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
        // the same two steps as confirm(): how each item's owners were decided, and who owes whom
        recordLabelSources(id)
        val confirmed = loadReceipts(listOf(id)).single()
        writeReceiptLedger(confirmed)
        ConfirmResult.Confirmed(confirmed)
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

    /** Voids the receipt and cancels any ledger entries it had, in one transaction. */
    override suspend fun void(id: Uuid): Receipt? = db.dbQuery {
        ReceiptTable.updateReturning(returning = listOf(ReceiptTable.id), where = { ReceiptTable.id eq id }) {
            it[status] = ReceiptStatus.VOID.dbValue
        }.toList()
        reverseReceiptLedger(id)
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
        val saved = SettlementTable.insertReturning {
            it[householdId] = settlement.householdId
            it[fromPersonId] = settlement.fromPersonId
            it[toPersonId] = settlement.toPersonId
            it[amountMinor] = settlement.amount.amountMinor
            it[currency] = settlement.amount.currency.code
            settlement.settledAt?.let { at -> it[settledAt] = at.toUtc() }
            it[note] = settlement.note
        }.single().toSettlement()

        // The payer's debt to the receiver shrinks, so the entry runs from receiver (debtor) to payer (creditor).
        insertLedgerEntry(
            NewLedgerEntry(
                householdId = saved.householdId,
                debtorPersonId = saved.toPersonId,
                creditorPersonId = saved.fromPersonId,
                amount = saved.amount,
                sourceType = LedgerSourceType.SETTLEMENT,
                sourceId = saved.id,
            ),
            null,
        )
        saved
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

class ExposedListItemRepository(private val db: Database) : ListItemRepository {
    override suspend fun add(item: NewListItem): ListItem = db.dbQuery {
        ListItemTable.insertReturning {
            it[householdId] = item.householdId
            it[text] = item.text
            it[quantity] = item.quantity
            it[notes] = item.notes
            it[addedByPersonId] = item.addedByPersonId
        }.single().toListItem()
    }

    override suspend fun findById(id: Uuid): ListItem? = db.dbQuery {
        ListItemTable.selectAll().where { ListItemTable.id eq id }.singleOrNull()?.toListItem()
    }

    override suspend fun listByHousehold(householdId: Uuid): List<ListItem> = db.dbQuery {
        ListItemTable.selectAll()
            .where { ListItemTable.householdId eq householdId }
            .orderBy(ListItemTable.createdAt to SortOrder.ASC, ListItemTable.id to SortOrder.ASC)
            .map { it.toListItem() }
            .sortedBy { it.status != ListItemStatus.OPEN }
    }

    override suspend fun update(id: Uuid, patch: ListItemPatch): ListItem? = db.dbQuery {
        val row = ListItemTable.selectAll().where { ListItemTable.id eq id }.forUpdate().singleOrNull()
            ?: return@dbQuery null
        val wasPurchased = ListItemStatus.fromDb(row[ListItemTable.status]) == ListItemStatus.PURCHASED
        // Checking off an item that is already checked off keeps the time it was first bought.
        val purchased = patch.purchased?.takeIf { it != wasPurchased }
        if (patch.copy(purchased = purchased).isEmpty) return@dbQuery row.toListItem()
        ListItemTable.updateReturning(where = { ListItemTable.id eq id }) {
            patch.text?.let { v -> it[text] = v }
            patch.quantity?.let { v -> it[quantity] = v.ifEmpty { null } }
            patch.notes?.let { v -> it[notes] = v.ifEmpty { null } }
            when (purchased) {
                true -> {
                    it[status] = ListItemStatus.PURCHASED.dbValue
                    it[purchasedAt] = CurrentTimestampWithTimeZone
                }
                false -> {
                    it[status] = ListItemStatus.OPEN.dbValue
                    it[purchasedAt] = null
                }
                null -> Unit
            }
        }.single().toListItem()
    }

    override suspend fun delete(id: Uuid): Boolean = db.dbQuery {
        ListItemTable.deleteWhere { ListItemTable.id eq id } > 0
    }

    override suspend fun autocomplete(householdId: Uuid, query: String, limit: Int): List<String> = db.dbQuery {
        val needle = query.trim().lowercase()
        val times = ReceiptItemTable.id.count()
        val names = ReceiptItemTable.join(ReceiptTable, JoinType.INNER, ReceiptItemTable.receiptId, ReceiptTable.id)
            .select(ReceiptItemTable.description, times)
            .where {
                (ReceiptItemTable.householdId eq householdId) and
                    (ReceiptTable.status eq ReceiptStatus.CONFIRMED.dbValue)
            }
        if (needle.isNotEmpty()) names.andWhere { ReceiptItemTable.description.lowerCase() like "%${needle.escapeLike()}%" }
        names.groupBy(ReceiptItemTable.description)
            .map { it[ReceiptItemTable.description].trim() to it[times] }
            // The same product written in different cases is one suggestion, shown as most often written.
            .groupBy { (name, _) -> name.lowercase() }
            .map { (_, spellings) -> spellings.maxBy { it.second }.first to spellings.sumOf { it.second } }
            .sortedWith(
                compareByDescending<Pair<String, Long>> { it.first.lowercase().startsWith(needle) }
                    .thenByDescending { it.second }
                    .thenBy { it.first.lowercase() },
            )
            .take(limit)
            .map { it.first }
    }
}

/** Escapes LIKE wildcards so a search for "50%" matches the text "50%" only. */
private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

class ExposedLedgerRepository(private val db: Database) : LedgerRepository {
    override suspend fun append(entry: NewLedgerEntry): LedgerEntry = db.dbQuery { insertLedgerEntry(entry, null) }

    override suspend fun reverse(entryId: Uuid): LedgerEntry? = db.dbQuery {
        val original = LedgerEntryTable.selectAll().where { LedgerEntryTable.id eq entryId }
            .singleOrNull()?.toLedgerEntry() ?: return@dbQuery null
        insertLedgerEntry(
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
}

// --- shared queries (must run inside a transaction) ------------------------------------------

private fun insertLedgerEntry(entry: NewLedgerEntry, reverses: Uuid?): LedgerEntry =
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

/** One entry per non-payer share of a just-confirmed receipt. */
private fun writeReceiptLedger(receipt: Receipt) {
    ledgerEntriesFor(receipt).forEach { insertLedgerEntry(it, reverses = null) }
}

/** Cancels every entry of the receipt that is not already cancelled. Safe to call twice. */
private fun reverseReceiptLedger(receiptId: Uuid) {
    val entries = LedgerEntryTable.selectAll()
        .where {
            (LedgerEntryTable.sourceType eq LedgerSourceType.RECEIPT.dbValue) and
                    (LedgerEntryTable.sourceId eq receiptId)
        }
        .map { it.toLedgerEntry() }
    val alreadyReversed = entries.mapNotNull { it.reversesEntryId }.toSet()
    entries.filter { it.reversesEntryId == null && it.id !in alreadyReversed }.forEach { original ->
        insertLedgerEntry(
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
}

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
    venmoUsername = this[PersonTable.venmoUsername],
    shortName = this[PersonTable.shortName],
    emoji = this[PersonTable.emoji],
    color = this[PersonTable.avatarColor],
    photoKey = this[PersonTable.photoKey],
    photoUpdatedAt = this[PersonTable.photoUpdatedAt]?.toInstant(),
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
        imageKey = this[ReceiptTable.imageKey],
        items = items,
        createdAt = this[ReceiptTable.createdAt].toInstant(),
        updatedAt = this[ReceiptTable.updatedAt].toInstant(),
        failureReason = this[ReceiptTable.failureReason],
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

private fun ResultRow.toListItem() = ListItem(
    id = this[ListItemTable.id],
    householdId = this[ListItemTable.householdId],
    text = this[ListItemTable.text],
    quantity = this[ListItemTable.quantity],
    notes = this[ListItemTable.notes],
    addedByPersonId = this[ListItemTable.addedByPersonId],
    status = ListItemStatus.fromDb(this[ListItemTable.status]),
    purchasedAt = this[ListItemTable.purchasedAt]?.toInstant(),
    createdAt = this[ListItemTable.createdAt].toInstant(),
    updatedAt = this[ListItemTable.updatedAt].toInstant(),
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
