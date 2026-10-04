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

/** A receipt cannot be confirmed while these items hold model predictions nobody has confirmed. */
class UnconfirmedItemsException(val itemIds: List<kotlin.uuid.Uuid>) :
    RuntimeException("${itemIds.size} item(s) need to be confirmed first")
