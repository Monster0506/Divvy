package com.monster0506.divvy.server.receipt

import com.monster0506.divvy.server.InvalidTransitionException

enum class ReceiptStatus(val wireName: String) {
    PROCESSING("processing"),
    NEEDS_REVIEW("needs_review"),
    CONFIRMED("confirmed"),
    FAILED("failed");

    // Statuses this one may move to; confirmed -> needs_review is the correction path.
    private val allowedNext: Set<ReceiptStatus>
        get() = when (this) {
            PROCESSING -> setOf(NEEDS_REVIEW, FAILED)
            NEEDS_REVIEW -> setOf(CONFIRMED, PROCESSING, FAILED)
            CONFIRMED -> setOf(NEEDS_REVIEW)
            FAILED -> setOf(PROCESSING)
        }

    fun canTransitionTo(next: ReceiptStatus): Boolean = next in allowedNext

    companion object {
        fun fromWireName(name: String): ReceiptStatus? = entries.firstOrNull { it.wireName == name }
    }
}

/** Returns [next] if the move is valid, otherwise throws [InvalidTransitionException]. */
fun ReceiptStatus.transitionTo(next: ReceiptStatus): ReceiptStatus {
    if (!canTransitionTo(next)) {
        throw InvalidTransitionException("Cannot move receipt from $wireName to ${next.wireName}")
    }
    return next
}
