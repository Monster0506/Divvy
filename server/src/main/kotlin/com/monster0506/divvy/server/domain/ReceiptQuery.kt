package com.monster0506.divvy.server.domain

import java.time.Instant
import kotlin.uuid.Uuid

/** Optional filters for listing receipts. [from] is inclusive and [to] is exclusive. */
data class ReceiptFilter(
    val status: ReceiptStatus? = null,
    /** What was typed in the store search; see [StoreSearch] for which names it finds. */
    val merchant: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val paidByPersonId: Uuid? = null,
)

/** Position after the last receipt of a page, in newest-first order. */
data class ReceiptCursor(val purchasedAt: Instant, val id: Uuid)

data class ReceiptPage(val receipts: List<Receipt>, val next: ReceiptCursor?)
