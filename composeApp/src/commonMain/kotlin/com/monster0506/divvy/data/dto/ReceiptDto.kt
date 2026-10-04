package com.monster0506.divvy.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The statuses the server reports. A new upload is [Processing], then [Draft] while it is being reviewed. */
@Serializable
enum class ReceiptStatusDto {
    @SerialName("processing") Processing,
    @SerialName("draft") Draft,
    @SerialName("confirmed") Confirmed,
    @SerialName("void") Void,
    @SerialName("failed") Failed,
}

/** Money as the server sends it: integer minor units (cents) in [currency]. */
@Serializable
data class MoneyDto(val amountMinor: Long, val currency: String = DEFAULT_CURRENCY)

/** `GET /receipts/{id}`. Timestamps are ISO-8601 strings. [failureReason] is set when [status] is Failed. */
@Serializable
data class ReceiptDto(
    val id: String,
    val householdId: String,
    val status: ReceiptStatusDto,
    val paidByPersonId: String? = null,
    val merchantName: String? = null,
    val purchasedAt: String? = null,
    val subtotal: MoneyDto? = null,
    val tax: MoneyDto? = null,
    val total: MoneyDto? = null,
    val confirmedAt: String? = null,
    val hasImage: Boolean = false,
    val items: List<ReceiptItemDto> = emptyList(),
    val failureReason: String? = null,
)

/** [rawText] is the line as printed; [description] is the readable name. [quantity] is a decimal string. */
@Serializable
data class ReceiptItemDto(
    val id: String,
    val description: String,
    val total: MoneyDto,
    val lineNumber: Int = 0,
    val rawText: String? = null,
    val quantity: String = "1",
    val unitPrice: MoneyDto? = null,
    val discount: MoneyDto? = null,
    val owners: List<ItemOwnerReadDto> = emptyList(),
    val labelSource: String? = null,
)

/** An owner on a receipt item as read back: [source] is `user` or `model`, [confidence] is set for the model. */
@Serializable
data class ItemOwnerReadDto(
    val personId: String,
    val shareWeight: Int = 1,
    val source: String = "user",
    val confidence: Double? = null,
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

/** `POST /receipts/{id}/confirm`. */
@Serializable
data class ConfirmedReceiptDto(
    val id: String,
    val status: ReceiptStatusDto,
    val confirmedAt: String? = null,
)

/** How an item's owners were guessed. [needsConfirmation] means a person must confirm it before the receipt can be confirmed. */
@Serializable
data class ItemPredictionDto(
    val itemId: String,
    val state: String,
    val ownerIds: List<String> = emptyList(),
    val confidence: Double? = null,
    val needsConfirmation: Boolean = false,
)

/** `GET /households/{id}/receipts/{receiptId}/predictions`. [notice] is set when guessing was not possible. */
@Serializable
data class PredictionsDto(
    val receiptId: String,
    val mlAvailable: Boolean = true,
    val modelVersion: String? = null,
    val notice: String? = null,
    val items: List<ItemPredictionDto> = emptyList(),
)

@Serializable
data class AcceptPredictionsRequest(val itemIds: List<String>? = null)

@Serializable
data class AcceptedPredictionsDto(val confirmedItemIds: List<String> = emptyList())

const val DEFAULT_CURRENCY = "USD"
