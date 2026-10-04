package com.monster0506.divvy.server.ledger

import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.domain.Receipt
import kotlin.uuid.Uuid

/** Minor units each person owes for [receipt], from its items' owners. Unowned items are skipped. */
fun receiptShares(receipt: Receipt): Map<Uuid, Long> {
    val owed = linkedMapOf<Uuid, Long>()
    for (item in receipt.items) {
        if (item.owners.isEmpty()) continue
        splitByWeight(item.total.amountMinor, item.owners.sortedBy { it.personId.toString() })
            .forEach { (person, cents) -> owed.merge(person, cents, Long::plus) }
    }
    return owed
}

/** Largest-remainder split: shares always add up to [total]; ties go to the lowest person id. */
private fun splitByWeight(total: Long, owners: List<ItemOwner>): Map<Uuid, Long> {
    val weightSum = owners.sumOf { it.shareWeight.toLong() }
    val floors = owners.map { Math.floorDiv(total * it.shareWeight, weightSum) }
    val fractions = owners.map { Math.floorMod(total * it.shareWeight, weightSum) }
    val shares = floors.toMutableList()
    var extra = (total - floors.sum()).toInt()
    for (i in owners.indices.sortedByDescending { fractions[it] }) { // stable sort keeps id order on ties
        if (extra == 0) break
        shares[i] += 1
        extra--
    }
    return owners.indices.associate { owners[it].personId to shares[it] }
}

fun ledgerEntriesFor(receipt: Receipt): List<NewLedgerEntry> {
    val payerId = receipt.paidByPersonId ?: return emptyList()
    return receiptShares(receipt)
        .filter { (person, cents) -> person != payerId && cents > 0 }
        .map { (person, cents) ->
            NewLedgerEntry(
                householdId = receipt.householdId,
                debtorPersonId = person,
                creditorPersonId = payerId,
                amount = Money(cents, receipt.currency),
                sourceType = LedgerSourceType.RECEIPT,
                sourceId = receipt.id,
            )
        }
}