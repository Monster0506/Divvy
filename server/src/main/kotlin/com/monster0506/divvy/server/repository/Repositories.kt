package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.ItemOwnerHistoryEntry
import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus

import com.monster0506.divvy.server.domain.ReceiptCursor
import com.monster0506.divvy.server.domain.ReceiptFilter
import com.monster0506.divvy.server.domain.ReceiptPage
import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.domain.Settlement
import kotlin.uuid.Uuid

/*
 * Data access interfaces for the core domain. Implementations live next to this file and use
 * Exposed; routes and services should depend on these interfaces only.
 *
 * Cross-household references (a payer, owner or settlement party from another household) are
 * rejected by the database's composite foreign keys and surface as an exception.
 */

interface HouseholdRepository {
    suspend fun create(name: String, defaultCurrency: CurrencyCode): Household
    suspend fun findById(id: Uuid): Household?
    suspend fun rename(id: Uuid, name: String): Household?

    /**
     * Permanently deletes a household and everything in it (people, receipts, items, ownership,
     * settlements). The ownership history log is kept. Returns false if it did not exist.
     */
    suspend fun delete(id: Uuid): Boolean
}

interface PersonRepository {
    suspend fun add(householdId: Uuid, displayName: String): Person
    suspend fun findById(id: Uuid): Person?

    /** Members of a household in the order they were added. */
    suspend fun listByHousehold(householdId: Uuid, includeArchived: Boolean = false): List<Person>

    suspend fun rename(id: Uuid, displayName: String): Person?

    /** Removes a person from the active household while keeping their history. */
    suspend fun archive(id: Uuid): Person?
}

interface ReceiptRepository {
    /**
     * Inserts a draft receipt, its line items (numbered in list order) and each item's owners in
     * one transaction.
     */
    suspend fun create(receipt: NewReceipt): Receipt

    /**
     * Inserts a receipt in `processing` status for an uploaded image, with no payer, items or
     * amounts yet. The household must exist.
     */
    suspend fun createProcessing(householdId: Uuid, imageKey: String): Receipt

    /** The receipt with its items and their current owners. */
    suspend fun findById(id: Uuid): Receipt?

    /** Receipts newest first, with items and owners. */
    suspend fun listByHousehold(householdId: Uuid, limit: Int = 50): List<Receipt>

    /** One page of receipts newest first, ordered by purchase time then id so pages never overlap. */
    suspend fun page(
        householdId: Uuid,
        filter: ReceiptFilter = ReceiptFilter(),
        after: ReceiptCursor? = null,
        limit: Int = 50,
    ): ReceiptPage

    /**
     * Confirms a draft receipt and records a [com.monster0506.divvy.server.domain.LabelSource]
     * for each item by comparing its final owners with what the model first predicted.
     */
    suspend fun confirm(id: Uuid): Receipt?

    /*
     * Edits below apply to draft receipts only and throw [ReceiptLockedException] otherwise.
     * They return the updated receipt, or null if the receipt (or the item) does not exist.
     */
    suspend fun update(id: Uuid, patch: ReceiptPatch): Receipt?
    suspend fun addItem(receiptId: Uuid, item: NewReceiptItem): Receipt?
    suspend fun updateItem(receiptId: Uuid, itemId: Uuid, patch: ReceiptItemPatch): Receipt?
    suspend fun deleteItem(receiptId: Uuid, itemId: Uuid): Receipt?

    /**
     * Combines two or more items into the first (lowest line number): totals, discounts and
     * quantities are summed and the first item's owners are kept. The others are removed.
     */
    suspend fun mergeItems(receiptId: Uuid, itemIds: List<Uuid>, description: String? = null): Receipt?

    /**
     * Confirms a draft receipt in one transaction if every item has at least one active owner.
     * Model-suggested owners become user-confirmed, which is recorded in the ownership history.
     */
    suspend fun confirmAssigned(id: Uuid): ConfirmResult

    /**
     * Share of model-predicted items on confirmed receipts that people changed, or null if there
     * are none yet.
     */
    suspend fun correctionRate(householdId: Uuid): Double?

    /** Marks a receipt void. Receipts are never deleted, so their history stays available. */
    suspend fun void(id: Uuid): Receipt?

    /** Moves a confirmed receipt back to draft for correction and reverses its ledger entries. */
    suspend fun reopen(id: Uuid): Receipt?
}

sealed interface ConfirmResult {
    data object NotFound : ConfirmResult
    data class NotDraft(val status: ReceiptStatus) : ConfirmResult

    /** [itemIds] have no active owner. */
    data class Unassigned(val itemIds: List<Uuid>) : ConfirmResult
    data class Confirmed(val receipt: Receipt) : ConfirmResult
}

/** The receipt is no longer a draft, so its contents cannot be edited. */
class ReceiptLockedException(message: String) : RuntimeException(message)

interface ItemOwnerRepository {
    /**
     * Replaces the owners of an item with [owners]: one person, several, or everyone.
     * Owners not in the list are removed; existing owners are updated in place. Every change is
     * recorded in the ownership history. An empty list leaves the item unassigned.
     */
    suspend fun setOwners(receiptItemId: Uuid, owners: List<OwnerShare>): List<ItemOwner>

    /**
     * Assigns an item to every active member of its household with equal shares. Members are
     * stored individually, so people who join later are not retroactively added.
     */
    suspend fun assignToAllMembers(
        receiptItemId: Uuid,
        source: OwnershipSource = OwnershipSource.USER,
    ): List<ItemOwner>

    suspend fun ownersOf(receiptItemId: Uuid): List<ItemOwner>

    /** Every ownership change for an item, oldest first. */
    suspend fun history(receiptItemId: Uuid): List<ItemOwnerHistoryEntry>
}

interface SettlementRepository {
    suspend fun record(settlement: NewSettlement): Settlement
    suspend fun findById(id: Uuid): Settlement?

    /** Settlements newest first. */
    suspend fun listByHousehold(householdId: Uuid): List<Settlement>
}

/** Insert and query only: ledger rows are never updated or deleted. */
interface LedgerRepository {
    suspend fun append(entry: NewLedgerEntry): LedgerEntry

    /** Appends an entry with debtor and creditor swapped that cancels [entryId]. Null if it does not exist. */
    suspend fun reverse(entryId: Uuid): LedgerEntry?
    suspend fun findById(id: Uuid): LedgerEntry?

    /** Entries oldest first. */
    suspend fun listByHousehold(householdId: Uuid): List<LedgerEntry>

    /** Entries created by one receipt or settlement, oldest first, including their reversals. */
    suspend fun listBySource(sourceType: LedgerSourceType, sourceId: Uuid): List<LedgerEntry>
}