package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

/** One entry on the household's shared grocery list. */
@Serializable
data class ListItemDto(
    val id: String,
    val householdId: String,
    val text: String,
    val quantity: String? = null,
    val notes: String? = null,
    val addedByPersonId: String? = null,
    val addedByName: String? = null,
    /** `open` or `purchased`. */
    val status: String = LIST_ITEM_OPEN,
    val purchasedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val purchased get() = status == LIST_ITEM_PURCHASED
}

const val LIST_ITEM_OPEN = "open"
const val LIST_ITEM_PURCHASED = "purchased"

/** `POST /households/{id}/list`. */
@Serializable
data class CreateListItemRequest(
    val text: String,
    val quantity: String? = null,
    val notes: String? = null,
    val addedByPersonId: String? = null,
)

/** `PATCH /households/{id}/list/{itemId}`: null fields are unchanged and an empty string clears one. */
@Serializable
data class UpdateListItemRequest(
    val text: String? = null,
    val quantity: String? = null,
    val notes: String? = null,
    val purchased: Boolean? = null,
)

@Serializable
data class AutocompleteDto(val suggestions: List<String> = emptyList())
