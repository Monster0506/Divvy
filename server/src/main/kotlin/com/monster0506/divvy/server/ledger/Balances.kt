package com.monster0506.divvy.server.ledger

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.LedgerEntry
import kotlin.uuid.Uuid

/** [debtorPersonId] owes [creditorPersonId] [amountMinor] (always positive) once opposite directions are netted. */
data class PairDebt(val debtorPersonId: Uuid, val creditorPersonId: Uuid, val amountMinor: Long)

/**
 * Balances in one currency. In [netByPerson] a positive amount means the person is owed money and a
 * negative one means they owe it; the amounts always sum to zero. [debts] are raw pairwise debts, not
 * simplified across people.
 */
data class CurrencyBalances(
    val currency: CurrencyCode,
    val netByPerson: Map<Uuid, Long>,
    val debts: List<PairDebt>,
)

/** Folds ledger entries into balances, one result per currency, ordered by currency code. */
fun balancesByCurrency(entries: List<LedgerEntry>): List<CurrencyBalances> =
    entries.groupBy { it.amount.currency }
        .map { (currency, rows) -> balancesOf(currency, rows) }
        .sortedBy { it.currency.code }

private fun balancesOf(currency: CurrencyCode, entries: List<LedgerEntry>): CurrencyBalances {
    val net = linkedMapOf<Uuid, Long>()
    val directed = hashMapOf<Pair<Uuid, Uuid>, Long>() // (debtor, creditor) -> amount
    for (entry in entries) {
        val cents = entry.amount.amountMinor
        net.merge(entry.creditorPersonId, cents, Long::plus)
        net.merge(entry.debtorPersonId, -cents, Long::plus)
        directed.merge(entry.debtorPersonId to entry.creditorPersonId, cents, Long::plus)
    }
    val debts = directed.keys
        .map { (x, y) -> if (x.toString() <= y.toString()) x to y else y to x }
        .distinct()
        .mapNotNull { (x, y) ->
            val diff = (directed[x to y] ?: 0L) - (directed[y to x] ?: 0L)
            when {
                diff > 0 -> PairDebt(x, y, diff)
                diff < 0 -> PairDebt(y, x, -diff)
                else -> null
            }
        }
        .sortedWith(compareBy({ it.debtorPersonId.toString() }, { it.creditorPersonId.toString() }))
    return CurrencyBalances(currency, net, debts)
}