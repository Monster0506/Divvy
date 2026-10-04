package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateHouseholdRequest(val name: String, val defaultCurrency: String = DEFAULT_CURRENCY)

@Serializable
data class RenameHouseholdRequest(val name: String)

@Serializable
data class PersonRequest(val displayName: String)
