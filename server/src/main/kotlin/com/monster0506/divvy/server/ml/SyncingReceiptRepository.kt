package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ConfirmResult
import com.monster0506.divvy.server.repository.ReceiptRepository
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(SyncingReceiptRepository::class.java)

    override suspend fun confirm(id: Uuid): Receipt? =
        delegate.confirm(id)?.also { receipt ->
            if (receipt.status == ReceiptStatus.CONFIRMED) {
                logLabelSources(receipt)
                sync.scheduleConfirmed(receipt)
            }
        }

    override suspend fun confirmAssigned(id: Uuid): ConfirmResult =
        delegate.confirmAssigned(id).also { result ->
            if (result is ConfirmResult.Confirmed) {
                logLabelSources(result.receipt)
                sync.scheduleConfirmed(result.receipt)
            }
        }

    override suspend fun void(id: Uuid): Receipt? =
        delegate.void(id)?.also { receipt ->
            if (receipt.items.any { it.labelSource != null }) sync.scheduleVoided(receipt)
        }

    /** One line per confirmed receipt: how many items were accepted from the model, changed, or typed in. */
    private fun logLabelSources(receipt: Receipt) {
        val sources = receipt.items.mapNotNull { it.labelSource }
        log.info(
            "Confirmed receipt {}: {} accepted, {} corrected, {} manual",
            receipt.id,
            sources.count { it == LabelSource.PREDICTED_ACCEPTED },
            sources.count { it == LabelSource.PREDICTED_CORRECTED },
            sources.count { it == LabelSource.MANUAL },
        )
    }
}
