package com.monster0506.divvy.server.domain

import java.math.BigDecimal
import java.time.Instant
import kotlin.uuid.Uuid

/** ISO 4217 currency code, e.g. `USD`. */
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "Invalid ISO 4217 currency code: $code" }
    }

    override fun toString(): String = code
}

/**
 * An amount of money in integer minor units (cents for USD) plus its currency.
 * Money is never represented as a floating-point number anywhere in Divy.
 */
data class Money(val amountMinor: Long, val currency: CurrencyCode)

data class Household(
    val id: Uuid,
    val name: String,
    val defaultCurrency: CurrencyCode,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A member of a household. Not a login account; a person may be linked to one later. */
data class Person(
    val id: Uuid,
    val householdId: Uuid,
    val displayName: String,
    /** Set when the person leaves the household. Archived people keep their history. */
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class ReceiptStatus(val dbValue: String) {
    DRAFT("draft"),
    CONFIRMED("confirmed"),

    /** Receipts are voided instead of deleted so their history is kept. */
    VOID("void");

    companion object {
        fun fromDb(value: String): ReceiptStatus = entries.first { it.dbValue == value }
    }
}

data class Receipt(
    val id: Uuid,
    val householdId: Uuid,
    val paidByPersonId: Uuid,
    val merchantName: String?,
    val purchasedAt: Instant,
    val subtotal: Money?,
    val tax: Money?,
    val total: Money,
    val status: ReceiptStatus,
    val confirmedAt: Instant?,
    val items: List<ReceiptItem>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val currency: CurrencyCode get() = total.currency
}

data class ReceiptItem(
    val id: Uuid,
    val receiptId: Uuid,
    val lineNumber: Int,
    /** The line exactly as read from the receipt; null for items entered by hand. */
    val rawText: String?,
    /** The cleaned-up name shown to people. */
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: Money?,
    /** Discount on this line, as a positive amount. [total] is after the discount. */
    val discount: Money,
    /** Line total. Negative for standalone coupon lines. */
    val total: Money,
    val owners: List<ItemOwner>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class OwnershipSource(val dbValue: String) {
    /** Chosen or confirmed by a person. */
    USER("user"),

    /** Predicted by the ownership model. */
    MODEL("model");

    companion object {
        fun fromDb(value: String): OwnershipSource = entries.first { it.dbValue == value }
    }
}

/** One owner of a receipt item. An item can have any number of owners from its household. */
data class ItemOwner(
    val receiptItemId: Uuid,
    val personId: Uuid,
    /** Relative share of the item; equal weights split it evenly. */
    val shareWeight: Int,
    val source: OwnershipSource,
    /** Model confidence in [0, 1] when [source] is [OwnershipSource.MODEL]. */
    val confidence: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A requested owner when assigning an item. */
data class OwnerShare(
    val personId: Uuid,
    val shareWeight: Int = 1,
    val source: OwnershipSource = OwnershipSource.USER,
    val confidence: BigDecimal? = null,
) {
    init {
        require(shareWeight > 0) { "shareWeight must be positive" }
        require(confidence == null || (confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE)) {
            "confidence must be within [0, 1]"
        }
    }
}

enum class OwnershipChange(val dbValue: String) {
    ASSIGNED("assigned"),
    CHANGED("changed"),
    UNASSIGNED("unassigned");

    companion object {
        fun fromDb(value: String): OwnershipChange = entries.first { it.dbValue == value }
    }
}

/** An entry in the append-only ownership log, used later as training data. */
data class ItemOwnerHistoryEntry(
    val id: Long,
    val receiptItemId: Uuid,
    val personId: Uuid,
    val change: OwnershipChange,
    val shareWeight: Int,
    val source: OwnershipSource,
    val confidence: BigDecimal?,
    val changedAt: Instant,
)

data class NewReceipt(
    val householdId: Uuid,
    val paidByPersonId: Uuid,
    val merchantName: String?,
    val purchasedAt: Instant,
    val currency: CurrencyCode,
    val subtotalMinor: Long?,
    val taxMinor: Long?,
    val totalMinor: Long,
    val items: List<NewReceiptItem>,
)

data class NewReceiptItem(
    val description: String,
    val totalMinor: Long,
    val quantity: BigDecimal = BigDecimal.ONE,
    val unitPriceMinor: Long? = null,
    val rawText: String? = null,
    val discountMinor: Long = 0,
    /** Owners written in the same transaction as the receipt. Empty leaves the item unassigned. */
    val owners: List<OwnerShare> = emptyList(),
) {
    init {
        require(discountMinor >= 0) { "discountMinor must not be negative" }
        require(owners.map { it.personId }.toSet().size == owners.size) { "Each person may own an item only once" }
    }
}

/** A payment from one member to another that reduces what [fromPersonId] owes [toPersonId]. */
data class Settlement(
    val id: Uuid,
    val householdId: Uuid,
    val fromPersonId: Uuid,
    val toPersonId: Uuid,
    val amount: Money,
    val settledAt: Instant,
    val note: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class NewSettlement(
    val householdId: Uuid,
    val fromPersonId: Uuid,
    val toPersonId: Uuid,
    val amount: Money,
    val settledAt: Instant? = null,
    val note: String? = null,
) {
    init {
        require(amount.amountMinor > 0) { "Settlement amount must be positive" }
        require(fromPersonId != toPersonId) { "A person cannot settle with themselves" }
    }
}