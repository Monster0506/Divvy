package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

/** One row of `GET /households/{id}/receipts`. [paidByPersonId] is null while a receipt is being read. */
@Serializable
data class ReceiptSummaryDto(
    val id: String,
    val purchasedAt: String,
    val currency: String = DEFAULT_CURRENCY,
    val totalMinor: Long = 0,
    val status: ReceiptStatusDto,
    val itemCount: Int = 0,
    val merchantName: String? = null,
    val paidByPersonId: String? = null,
)

/** A page of receipts, newest first. Pass [nextCursor] back to get the next page. */
@Serializable
data class ReceiptPageDto(
    val items: List<ReceiptSummaryDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class EditedOwnerDto(val personId: String, val shareWeight: Int = 1)

/** A line as the edit endpoints return it. */
@Serializable
data class EditedItemDto(
    val id: String,
    val description: String,
    val totalMinor: Long,
    val lineNumber: Int = 0,
    val rawText: String? = null,
    val quantity: Double = 1.0,
    val unitPriceMinor: Long? = null,
    val discountMinor: Long = 0,
    val owners: List<EditedOwnerDto> = emptyList(),
)

/** How far the lines are from the receipt's printed amounts. [reconciled] is true when they agree. */
@Serializable
data class ReconciliationDto(
    val itemsTotalMinor: Long = 0,
    val totalDifferenceMinor: Long = 0,
    val subtotalDifferenceMinor: Long? = null,
    val reconciled: Boolean = true,
)

/** The receipt after an edit, with its lines and whether they still add up. */
@Serializable
data class EditedReceiptDto(
    val id: String,
    val householdId: String,
    val totalMinor: Long = 0,
    val status: ReceiptStatusDto,
    val items: List<EditedItemDto> = emptyList(),
    val reconciliation: ReconciliationDto = ReconciliationDto(),
    val paidByPersonId: String? = null,
    val merchantName: String? = null,
    val purchasedAt: String? = null,
    val currency: String = DEFAULT_CURRENCY,
    val subtotalMinor: Long? = null,
    val taxMinor: Long? = null,
)

/** Fields to change on a line, or the fields of a new one. Leave out what stays as it is. */
@Serializable
data class ItemEditRequest(
    val description: String? = null,
    val totalMinor: Long? = null,
    val quantity: Double? = null,
    val unitPriceMinor: Long? = null,
    val discountMinor: Long? = null,
)

/** `PATCH /receipts/{id}`: the receipt's own fields. Leave out what stays as it is. */
@Serializable
data class UpdateReceiptRequest(
    val merchantName: String? = null,
    val purchasedAt: String? = null,
    val taxMinor: Long? = null,
    val totalMinor: Long? = null,
)

@Serializable
data class MergeItemsRequest(val itemIds: List<String>, val description: String? = null)

/** `GET` and `PUT /households/{id}/ml/settings`. The threshold is a fraction between 0 and 1. */
@Serializable
data class MlSettingsDto(val autoAssign: Boolean = true, val autoAssignThreshold: Double = 0.85)
