package com.monster0506.divvy.domain

import com.monster0506.divvy.data.dto.ItemOwnerReadDto
import com.monster0506.divvy.data.dto.ReceiptItemDto

/**
 * Cents each person owes for a receipt's lines, split by share weight. This is the server's rule so the numbers
 * agree with the balances it records: every line's shares add up to the line's total, and an odd cent goes to the
 * largest remainder, ties to the lowest person id. A line nobody owns is skipped.
 */
fun receiptShares(items: List<ReceiptItemDto>): Map<String, Long> {
    val owed = linkedMapOf<String, Long>()
    for (item in items) {
        if (item.owners.isEmpty()) continue
        splitByWeight(item.total.amountMinor, item.owners.sortedBy { it.personId }).forEach { (person, cents) ->
            owed[person] = (owed[person] ?: 0L) + cents
        }
    }
    return owed
}

private fun splitByWeight(total: Long, owners: List<ItemOwnerReadDto>): Map<String, Long> {
    val weightSum = owners.sumOf { it.shareWeight.toLong() }
    val floors = owners.map { floorDiv(total * it.shareWeight, weightSum) }
    val fractions = owners.map { floorMod(total * it.shareWeight, weightSum) }
    val shares = floors.toMutableList()
    var extra = (total - floors.sum()).toInt()
    for (i in owners.indices.sortedByDescending { fractions[it] }) {
        if (extra == 0) break
        shares[i] += 1
        extra--
    }
    return owners.indices.associate { owners[it].personId to shares[it] }
}

private fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)

private fun floorMod(a: Long, b: Long): Long = a.mod(b)
