package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.ReceiptLine
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

/** The price of one: as printed or typed, or else the total before the discount shared across the quantity. */
val ReceiptLine.eachCents: Int get() = unitCents ?: ((priceCents + discountCents) / quantity).roundToInt()

/** What [quantity] of this line at [eachCents] come to, after the line's discount. */
fun ReceiptLine.totalFor(quantity: Double, eachCents: Int): Int = (eachCents * quantity).roundToInt() - discountCents

/** The line renamed and re-priced; the total follows from [quantity] and [eachCents]. */
fun ReceiptLine.edited(name: String, quantity: Double, eachCents: Int): ReceiptLine =
    copy(name = name, quantity = quantity, unitCents = eachCents, priceCents = totalFor(quantity, eachCents))

fun List<ReceiptLine>.editLine(id: Int, name: String, quantity: Double, eachCents: Int): List<ReceiptLine> =
    map { if (it.id == id) it.edited(name, quantity, eachCents) else it }

/** 2.0 -> "2", 1.235 -> "1.235"; weighed amounts keep up to three decimals. */
fun formatQuantity(quantity: Double): String {
    val thousandths = (quantity * 1000).roundToLong()
    val whole = thousandths / 1000
    val part = thousandths % 1000
    return if (part == 0L) "$whole" else "$whole." + part.toString().padStart(3, '0').trimEnd('0')
}

fun List<ReceiptLine>.nextLineId(): Int = (maxOfOrNull { it.id } ?: -1) + 1
