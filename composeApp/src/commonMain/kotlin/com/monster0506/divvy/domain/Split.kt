package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.weightOf

/**
 * What each person owes for [draft], in cents, in the order of [ReceiptDraft.people].
 *
 * Every line is split among its owners (everyone if it has none yet) in proportion to their share weights, which
 * are equal unless a person changed them. Leftover cents go to the largest remainders, ties to the first people in
 * line, so a line's shares always add up to its price. Tax is spread in proportion to each person's items.
 */
fun ReceiptDraft.shares(): List<Pair<Person, Int>> {
    if (people.isEmpty()) return emptyList()
    val owed = people.associate { it.id to 0 }.toMutableMap()
    lines.forEach { line ->
        val owners = people.filter { it.id in line.owners }.ifEmpty { people }
        val weights = owners.map { line.weightOf(it.id).coerceAtLeast(1).toLong() }
        val weightSum = weights.sum()
        val price = line.priceCents.toLong()
        val parts = weights.map { price.floorDiv(weightSum) * it + (price.mod(weightSum) * it).floorDiv(weightSum) }.toMutableList()
        val fractions = weights.map { (price.mod(weightSum) * it).mod(weightSum) }
        var extra = (price - parts.sum()).toInt()
        for (i in owners.indices.sortedByDescending { fractions[it] }) {
            if (extra <= 0) break
            parts[i] += 1
            extra--
        }
        owners.forEachIndexed { i, p -> owed[p.id] = owed.getValue(p.id) + parts[i].toInt() }
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
