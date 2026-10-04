package com.monster0506.divvy.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReceiptStatusDto {
    @SerialName("processing") Processing,
    @SerialName("needs_review") NeedsReview,
    @SerialName("confirmed") Confirmed,
    @SerialName("failed") Failed,
}

/** Money fields are integer minor units (cents) in [currency]; timestamps are ISO-8601 strings. */
@Serializable
data class ReceiptDto(
    val id: String,
    val householdId: String,
    val status: ReceiptStatusDto,
    val store: String? = null,
    val purchasedAt: String? = null,
    val payerId: String? = null,
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val discountCents: Long? = null,
    val totalCents: Long? = null,
    val currency: String = DEFAULT_CURRENCY,
    val imageUrl: String? = null,
    val items: List<ReceiptItemDto> = emptyList(),
)

@Serializable
data class ReceiptItemDto(
    val id: String,
    val rawText: String,
    val description: String,
    val quantity: Double = 1.0,
    val unitPriceCents: Long? = null,
    val totalPriceCents: Long,
    val discountCents: Long = 0,
    val owners: List<ItemOwnerDto> = emptyList(),
)

/** An item can have any number of owners; [weight] defaults to an equal split. */
@Serializable
data class ItemOwnerDto(
    val personId: String,
    val weight: Int = 1,
)

/** Response of setting an item's owners. */
@Serializable
data class ItemOwnersDto(
    val itemId: String,
    val owners: List<ItemOwnerDto> = emptyList(),
)

const val DEFAULT_CURRENCY = "USD"
