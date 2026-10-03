package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

/** Error body returned by the server; mirrors the server's `ApiError`. */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
)
