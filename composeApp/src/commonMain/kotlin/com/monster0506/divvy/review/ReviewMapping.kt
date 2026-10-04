package com.monster0506.divvy.review

import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.ReconciliationDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptItemDto
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.model.toMoney

fun PersonDto.toUiPerson(index: Int) =
    Person(id, displayName, initialsFor(displayName), AvatarColor.entries[index % AvatarColor.entries.size])

/**
 * The review screen's view of a server receipt. A line's id is its line number, and [ReviewLines.itemIds] maps it
 * back to the server item. A line is confirmed when someone owns it and the server does not ask for confirmation.
 */
data class ReviewLines(val draft: ReceiptDraft, val itemIds: Map<Int, String>)

fun buildReviewLines(
    receipt: ReceiptDto,
    people: List<Person>,
    payer: Person,
    predictions: PredictionsDto?,
): ReviewLines {
    val needsConfirmation = predictions?.items.orEmpty().filter { it.needsConfirmation }.map { it.itemId }.toSet()
    val ordered = receipt.items.sortedBy { it.lineNumber }
    val ids = ordered.mapIndexed { index, item -> lineId(item, index) to item.id }.toMap()
    val lines = ordered.mapIndexed { index, item ->
        val owners = item.owners.map { it.personId }.toSet()
        ReceiptLine(
            id = lineId(item, index),
            name = item.description,
            priceCents = item.total.amountMinor.toInt(),
            owners = owners,
            confidence = confidenceOf(item),
            confirmed = owners.isNotEmpty() && item.id !in needsConfirmation,
        )
    }
    val draft = ReceiptDraft(
        store = receipt.merchantName ?: "Receipt",
        date = receipt.purchasedAt?.let(::formatDate) ?: "",
        payer = payer,
        people = people,
        lines = lines,
        taxCents = receipt.tax?.amountMinor?.toInt() ?: 0,
    )
    return ReviewLines(draft, ids)
}

/** How the lines compare with the printed amounts, worked out the way the server does after an edit. */
fun reconcile(receipt: ReceiptDto): ReconciliationDto {
    val items = receipt.items.sumOf { it.total.amountMinor }
    val tax = receipt.tax?.amountMinor ?: 0L
    val total = receipt.total?.amountMinor
    return ReconciliationDto(
        itemsTotalMinor = items,
        totalDifferenceMinor = if (total == null) 0 else total - (items + tax),
        subtotalDifferenceMinor = receipt.subtotal?.let { it.amountMinor - items },
        reconciled = (total == null || total - (items + tax) == 0L) && (receipt.subtotal?.let { it.amountMinor - items } ?: 0L) == 0L,
    )
}

/** A sentence for a person when the lines do not add up to what the receipt says, or null when they do. */
fun reconciliationWarning(reconciliation: ReconciliationDto): String? {
    if (reconciliation.reconciled) return null
    val items = reconciliation.itemsTotalMinor
    val subtotalGap = reconciliation.subtotalDifferenceMinor?.takeIf { it != 0L }
    val gap = subtotalGap ?: reconciliation.totalDifferenceMinor
    val label = if (subtotalGap != null) "subtotal" else "total"
    val direction = if (gap > 0) "less" else "more"
    return "The lines add up to ${items.toInt().toMoney()}, which is ${kotlin.math.abs(gap).toInt().toMoney()} $direction than the receipt $label."
}

private fun lineId(item: ReceiptItemDto, index: Int) = if (item.lineNumber > 0) item.lineNumber else index + 1

/** The model's confidence for a guessed line; 1 for a line a person chose; 0 when nobody owns it. */
private fun confidenceOf(item: ReceiptItemDto): Float {
    if (item.owners.isEmpty()) return 0f
    val guesses = item.owners.filter { it.source == "model" }
    if (guesses.size < item.owners.size) return 1f
    return (guesses.mapNotNull { it.confidence }.minOrNull() ?: 0.0).toFloat()
}

private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-09-18T12:00:00Z" becomes "Sep 18, 2026"; anything else is shown as it came. */
fun formatDate(iso: String): String {
    val parts = iso.take(10).split("-")
    val year = parts.getOrNull(0)?.toIntOrNull()
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    if (parts.size != 3 || year == null || month == null || day == null || month !in 1..12) return iso
    return "${months[month - 1]} $day, $year"
}
