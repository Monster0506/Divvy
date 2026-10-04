package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.ReceiptLine

/** Parses user-typed money like "4.5" or "$12.99" into cents, or null if it is not a valid non-negative amount. */
fun parsePriceCents(input: String): Int? {
    val text = input.trim().removePrefix("$")
    val match = Regex("""(\d{0,7})(?:\.(\d{1,2}))?""").matchEntire(text) ?: return null
    val whole = match.groupValues[1]
    val frac = match.groupValues[2]
    if (whole.isEmpty() && frac.isEmpty()) return null
    return whole.ifEmpty { "0" }.toInt() * 100 + frac.padEnd(2, '0').toInt()
}

/** Formats cents as a plain editable amount, e.g. 1299 -> "12.99". */
fun centsToInput(cents: Int): String = "${cents / 100}." + (cents % 100).toString().padStart(2, '0')

fun List<ReceiptLine>.editLine(id: Int, name: String, priceCents: Int): List<ReceiptLine> =
    map { if (it.id == id) it.copy(name = name, priceCents = priceCents) else it }

/** Merges the lines in [ids] into the first of them: prices add up and owners are combined. */
fun List<ReceiptLine>.mergeLines(ids: Set<Int>): List<ReceiptLine> {
    val picked = filter { it.id in ids }
    if (picked.size < 2) return this
    val target = picked.first()
    val merged = target.copy(
        priceCents = picked.sumOf { it.priceCents },
        owners = picked.flatMapTo(linkedSetOf()) { it.owners },
        confidence = picked.minOf { it.confidence },
    )
    return mapNotNull { if (it.id == target.id) merged else if (it.id in ids) null else it }
}

fun List<ReceiptLine>.nextLineId(): Int = (maxOfOrNull { it.id } ?: -1) + 1
