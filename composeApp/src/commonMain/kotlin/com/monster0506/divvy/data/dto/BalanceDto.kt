package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

/** One counterparty and the amount, in minor units, owed between them and the person it sits under. */
@Serializable
data class DebtDto(val personId: String, val amountMinor: Long)

/** Positive [netMinor] means the person is owed money; negative means they owe. */
@Serializable
data class PersonBalanceDto(
    val personId: String,
    val displayName: String,
    val netMinor: Long,
    val owes: List<DebtDto> = emptyList(),
    val owedBy: List<DebtDto> = emptyList(),
    val venmoUsername: String? = null,
)

@Serializable
data class CurrencyBalancesDto(val currency: String, val people: List<PersonBalanceDto>)

@Serializable
data class BalancesDto(val balances: List<CurrencyBalancesDto>)

/** A payment between two people, as the server records it. */
@Serializable
data class SettlementDto(
    val id: String,
    val householdId: String,
    val fromPersonId: String,
    val toPersonId: String,
    val amountMinor: Long,
    val currency: String = DEFAULT_CURRENCY,
    val settledAt: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
)

/** `POST /households/{id}/settlements`: [fromPersonId] paid [toPersonId] [amountMinor]. */
@Serializable
data class CreateSettlementRequest(
    val fromPersonId: String,
    val toPersonId: String,
    val amountMinor: Long,
    val note: String? = null,
)
