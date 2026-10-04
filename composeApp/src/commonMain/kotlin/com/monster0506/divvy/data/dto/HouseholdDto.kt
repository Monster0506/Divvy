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
) {
    val isArchived: Boolean get() = archivedAt != null
}
