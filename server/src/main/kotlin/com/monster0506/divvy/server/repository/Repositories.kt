package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.ItemOwnerHistoryEntry
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptCursor
import com.monster0506.divvy.server.domain.ReceiptFilter
import com.monster0506.divvy.server.domain.ReceiptPage
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

    /** Removes a person from the active household while keeping their history. */
    suspend fun archive(id: Uuid): Person?
}

interface ReceiptRepository {
    /**
     * Inserts a draft receipt, its line items (numbered in list order) and each item's owners in
     * one transaction.
     */
    suspend fun create(receipt: NewReceipt): Receipt

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

    suspend fun confirm(id: Uuid): Receipt?

    /** Marks a receipt void. Receipts are never deleted, so their history stays available. */
    suspend fun void(id: Uuid): Receipt?
}

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