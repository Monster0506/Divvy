package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class HouseholdDto(
    val id: String,
    val name: String,
    val people: List<PersonDto> = emptyList(),
    val createdAt: String? = null,
)

@Serializable
data class PersonDto(
    val id: String,
    val householdId: String,
    val displayName: String,
    val archivedAt: String? = null,
    /** Where the person receives payments, without the "@". */
    val venmoUsername: String? = null,
    /** One or two characters shown in their avatar instead of initials from the name. */
    val shortName: String? = null,
    /** An icon shown in their avatar when there is no photo. */
    val emoji: String? = null,
    /** Their avatar color, as an [com.monster0506.divvy.ui.model.AvatarColor] key. */
    val color: String? = null,
    /** Set when they have a photo; changes whenever it is replaced. */
    val photoUpdatedAt: String? = null,
) {
    val isArchived: Boolean get() = archivedAt != null
}
