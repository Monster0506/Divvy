package com.monster0506.divvy.server

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
)

class NotFoundException(message: String) : RuntimeException(message)

class ValidationException(message: String, val details: List<String> = emptyList()) :
    RuntimeException(message)

class InvalidTransitionException(message: String) : RuntimeException(message)
