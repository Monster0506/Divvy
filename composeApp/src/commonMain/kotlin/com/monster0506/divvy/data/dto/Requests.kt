package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateHouseholdRequest(val name: String, val defaultCurrency: String = DEFAULT_CURRENCY)

@Serializable
data class RenameHouseholdRequest(val name: String)

@Serializable
data class PersonRequest(val displayName: String)

/** `PATCH /households/{id}/people/{personId}`; an empty [venmoUsername] removes it. */
@Serializable
data class VenmoUsernameRequest(val venmoUsername: String)

/** `PATCH /households/{id}/people/{personId}`: null fields stay as they are and an empty string clears one. */
@Serializable
data class AppearanceRequest(val shortName: String? = null, val emoji: String? = null, val color: String? = null)

@Serializable
data class SetReceiptPayerRequest(val paidByPersonId: String)

@Serializable
data class SetItemOwnersRequest(val owners: List<ItemOwnerDto>)
