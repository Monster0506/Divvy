package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.ReceiptDraft

/**
 * What each person owes for [draft], in cents, in the order of [ReceiptDraft.people].
 *
 * Every line is split evenly among its owners (everyone if it has none yet) and tax is spread in proportion to
 * each person's items. Remainder cents go to the first people in line, so the shares always add up to the total.
 */
fun ReceiptDraft.shares(): List<Pair<com.monster0506.divvy.ui.model.Person, Int>> {
    if (people.isEmpty()) return emptyList()
    val owed = people.associate { it.id to 0 }.toMutableMap()
    lines.forEach { line ->
        val owners = people.filter { it.id in line.owners }.ifEmpty { people }
        val each = line.priceCents / owners.size
        val extra = line.priceCents % owners.size
        owners.forEachIndexed { i, p -> owed[p.id] = owed.getValue(p.id) + each + if (i < extra) 1 else 0 }
    }
    val itemTotal = owed.values.sum()
    if (itemTotal > 0 && taxCents > 0) {
        val taxed = people.map { p -> p to (taxCents.toLong() * owed.getValue(p.id) / itemTotal).toInt() }
        var left = taxCents - taxed.sumOf { it.second }
        taxed.forEach { (p, tax) ->
            val extra = if (left > 0) 1 else 0
            left -= extra
            owed[p.id] = owed.getValue(p.id) + tax + extra
        }
    }
    return people.map { it to owed.getValue(it.id) }
}
