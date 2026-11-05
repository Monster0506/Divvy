package com.monster0506.divvy.ui.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.abs

/** Avatar background tints; text on all of them stays legible in [com.monster0506.divvy.ui.theme.DivvyColors.Ink]. */
enum class AvatarColor(val fill: Color) {
    Gray(Color(0xFFECEEF0)),
    Green(Color(0xFFD6EDE1)),
    Teal(Color(0xFFD3ECEC)),
    Blue(Color(0xFFDCE8F7)),
    Purple(Color(0xFFE8E0F5)),
    Pink(Color(0xFFF8DCE6)),
    Orange(Color(0xFFFBE3CF)),
    Yellow(Color(0xFFF7EDC6)),
}

/** An avatar shows [photo] if set, otherwise [emoji], otherwise [initials] on [color]. */
data class Person(
    val id: String,
    val name: String,
    val initials: String,
    val color: AvatarColor = AvatarColor.Gray,
    val emoji: String? = null,
    val photo: ImageBitmap? = null,
)

/** "Sumedh" -> "Su", "Mary Jane" -> "MJ". */
fun initialsFor(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercaseChar() }
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

data class Settings(
    val autoAssign: Boolean = true,
    /** Minimum model confidence for an item to be assigned without review. */
    val autoAssignThreshold: Float = 0.85f,
)

/**
 * One receipt line. [priceCents] is the line total after its discount; [quantity] can be fractional for weighed
 * items. [unitCents] is the price of one when the receipt printed it or a person typed it.
 */
data class ReceiptLine(
    val id: Int,
    val name: String,
    val priceCents: Int,
    val owners: Set<String>,
    val confidence: Float,
    val confirmed: Boolean,
    val quantity: Double = 1.0,
    val unitCents: Int? = null,
    val discountCents: Int = 0,
)

data class ReceiptDraft(
    val store: String,
    val date: String,
    val payer: Person,
    val people: List<Person>,
    val lines: List<ReceiptLine>,
    val taxCents: Int,
    /** False while nobody has been picked as the payer; [payer] is then only a placeholder. */
    val payerChosen: Boolean = true,
    /** The purchase date as `YYYY-MM-DD`, for editing; [date] is how it is shown. */
    val dateIso: String? = null,
    /** The total printed on the receipt, which the lines plus tax should add up to. */
    val printedTotalCents: Int? = null,
) {
    val subtotalCents get() = lines.sumOf { it.priceCents }
    val totalCents get() = subtotalCents + taxCents
}

data class Balance(val person: Person, val netCents: Int)

data class Transfer(val from: Person, val to: Person, val amountCents: Int, val paid: Boolean = false)

enum class ReceiptStatus(val label: String) {
    NeedsReview("Needs review"),
    Confirmed("Confirmed"),
    Reading("Reading..."),
    Failed("Could not read"),
    Voided("Voided"),
}

data class HistoryEntry(
    val store: String,
    val date: String,
    val totalCents: Int,
    val payer: Person,
    val status: ReceiptStatus,
)

/** One entry on the grocery list. [done] means it has been bought. */
data class ShoppingItem(
    val id: String,
    val name: String,
    val done: Boolean,
    /** Free text such as "2" or "1 dozen". */
    val quantity: String? = null,
    val notes: String? = null,
    /** Name of whoever put it on the list. */
    val addedBy: String? = null,
    val addedById: String? = null,
    /** When it was put on the list, as an ISO-8601 instant. */
    val addedAt: String? = null,
)

data class Suggestion(val name: String, val reason: String, val added: Boolean = false)

fun Int.toMoney(): String {
    val a = abs(this)
    return (if (this < 0) "-" else "") + "$" + a / 100 + "." + (a % 100).toString().padStart(2, '0')
}

fun ReceiptLine.ownersLabel(people: List<Person>): String = when {
    owners.isEmpty() -> "Nobody"
    owners.size == people.size -> "Everyone"
    else -> people.filter { it.id in owners }.joinToString(" + ") { it.name }
}
