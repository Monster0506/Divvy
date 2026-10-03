package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

/** Positive [netCents] means the person is owed money; negative means they owe. */
@Serializable
data class BalanceDto(
    val personId: String,
    val netCents: Long,
    val currency: String = DEFAULT_CURRENCY,
)

@Serializable
data class SettlementDto(
    val id: String,
    val householdId: String,
    val fromPersonId: String,
    val toPersonId: String,
    val amountCents: Long,
    val currency: String = DEFAULT_CURRENCY,
    val createdAt: String? = null,
)
