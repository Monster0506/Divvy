package com.monster0506.divvy.server.balances

data class Transfer(val from: String, val to: String, val amountCents: Long)

object DebtSimplifier {
    /**
     * Net balances in cents per person: positive means owed money, negative means owes money.
     * Repeatedly matches the largest creditor with the largest debtor.
     */
    fun simplify(balances: Map<String, Long>): List<Transfer> {
        require(balances.values.sum() == 0L) { "Balances must sum to zero" }

        val creditors = balances.filterValues { it > 0 }.toMutableMap()
        val debtors = balances.filterValues { it < 0 }.mapValues { -it.value }.toMutableMap()
        val order = compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }
        val transfers = mutableListOf<Transfer>()

        while (creditors.isNotEmpty() && debtors.isNotEmpty()) {
            val creditor = creditors.entries.sortedWith(order).first()
            val debtor = debtors.entries.sortedWith(order).first()
            val amount = minOf(creditor.value, debtor.value)
            transfers += Transfer(from = debtor.key, to = creditor.key, amountCents = amount)

            val creditorLeft = creditor.value - amount
            val debtorLeft = debtor.value - amount
            val creditorId = creditor.key
            val debtorId = debtor.key
            if (creditorLeft == 0L) creditors.remove(creditorId) else creditors[creditorId] = creditorLeft
            if (debtorLeft == 0L) debtors.remove(debtorId) else debtors[debtorId] = debtorLeft
        }
        return transfers
    }
}
