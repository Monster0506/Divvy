package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.UnconfirmedItemsException
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlin.uuid.Uuid

/**
 * Wraps a [ReceiptRepository] so a draft receipt cannot be confirmed while it still holds model
 * predictions a person has not confirmed. Assigning an item (even to the same owners) counts as
 * confirming it. Wrap this around the label sync so nothing is pushed for a refused confirm.
 */
class ConfirmationGuardReceiptRepository(
    private val delegate: ReceiptRepository,
    private val settings: MlSettingsRepository,
) : ReceiptRepository by delegate {
    override suspend fun confirm(id: Uuid): Receipt? {
        val receipt = delegate.findById(id) ?: return null
        if (receipt.status == ReceiptStatus.DRAFT) {
            val rules = settings.get(receipt.householdId)
            val pending = receipt.items.filter(rules::needsConfirmation)
            if (pending.isNotEmpty()) throw UnconfirmedItemsException(pending.map { it.id })
        }
        return delegate.confirm(id)
    }
}
