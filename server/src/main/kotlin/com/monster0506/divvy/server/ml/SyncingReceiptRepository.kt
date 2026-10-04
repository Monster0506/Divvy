package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlin.uuid.Uuid

/**
 * Wraps a [ReceiptRepository] so that confirming a receipt pushes its labels to the ML service and
 * voiding one removes them. Routes keep calling `receipts.confirm(id)` and need no ML code, and any
 * route added later gets the same behaviour. Everything else passes straight through.
 */
class SyncingReceiptRepository(
    private val delegate: ReceiptRepository,
    private val sync: LabelSync,
) : ReceiptRepository by delegate {
    override suspend fun confirm(id: Uuid): Receipt? =
        delegate.confirm(id)?.also { receipt ->
            if (receipt.status == ReceiptStatus.CONFIRMED) sync.scheduleConfirmed(receipt)
        }

    override suspend fun void(id: Uuid): Receipt? =
        delegate.void(id)?.also { receipt ->
            if (receipt.items.any { it.labelSource != null }) sync.scheduleVoided(receipt)
        }
}
