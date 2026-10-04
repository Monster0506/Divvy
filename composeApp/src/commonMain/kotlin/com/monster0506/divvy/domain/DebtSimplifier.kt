package com.monster0506.divvy.domain

/** A single payment of [amountCents] from [fromPersonId] to [toPersonId]. */
data class Transfer(
    val fromPersonId: String,
    val toPersonId: String,
    val amountCents: Long,
)

private val largestFirst = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }

/**
 * Turns net balances (positive = owed money, negative = owes) into a small set of transfers.
 * Greedily matches the largest creditor with the largest debtor, so N non-zero balances
 * need at most N - 1 transfers. Ties are broken by person id so output is deterministic.
 */
fun simplifyDebts(netCentsByPerson: Map<String, Long>): List<Transfer> {
    val creditors = netCentsByPerson.filterValues { it > 0 }.toList().sortedWith(largestFirst).toMutableList()
    val debtors = netCentsByPerson.filterValues { it < 0 }.map { it.key to -it.value }
        .sortedWith(largestFirst).toMutableList()
    require(creditors.sumOf { it.second } == debtors.sumOf { it.second }) { "Balances must sum to zero" }

    val transfers = mutableListOf<Transfer>()
    while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
        val (creditor, owed) = creditors.removeAt(0)
        val (debtor, owes) = debtors.removeAt(0)
        val amount = minOf(owed, owes)
        transfers += Transfer(fromPersonId = debtor, toPersonId = creditor, amountCents = amount)
        if (owed > amount) creditors.insertSorted(creditor to owed - amount)
        if (owes > amount) debtors.insertSorted(debtor to owes - amount)
    }
    return transfers
}

private fun MutableList<Pair<String, Long>>.insertSorted(entry: Pair<String, Long>) {
    val index = indexOfFirst { largestFirst.compare(entry, it) < 0 }
    add(if (index == -1) size else index, entry)
}
