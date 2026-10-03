package com.monster0506.divvy.ui.model

import kotlin.math.abs

data class Person(val id: String, val name: String, val initials: String)

data class ReceiptLine(
    val id: Int,
    val name: String,
    val priceCents: Int,
    val owners: Set<String>,
    val confidence: Float,
    val confirmed: Boolean,
)

data class ReceiptDraft(
    val store: String,
    val date: String,
    val payer: Person,
    val people: List<Person>,
    val lines: List<ReceiptLine>,
    val taxCents: Int,
) {
    val subtotalCents get() = lines.sumOf { it.priceCents }
    val totalCents get() = subtotalCents + taxCents
}

data class Balance(val person: Person, val netCents: Int)

data class Transfer(val from: Person, val to: Person, val amountCents: Int, val paid: Boolean = false)

enum class ReceiptStatus(val label: String) { NeedsReview("Needs review"), Confirmed("Confirmed") }

data class HistoryEntry(
    val store: String,
    val date: String,
    val totalCents: Int,
    val payer: Person,
    val status: ReceiptStatus,
)

data class ShoppingItem(val name: String, val done: Boolean)

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
