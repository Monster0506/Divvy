package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.UnconfirmedItemsException
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ConfirmResult
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
        refuseIfUnconfirmed(id)
        return delegate.confirm(id)
    }

    override suspend fun confirmAssigned(id: Uuid): ConfirmResult {
        refuseIfUnconfirmed(id)
        return delegate.confirmAssigned(id)
    }

    private suspend fun refuseIfUnconfirmed(id: Uuid) {
        val receipt = delegate.findById(id) ?: return
        if (receipt.status != ReceiptStatus.DRAFT) return
        val rules = settings.get(receipt.householdId)
        val pending = receipt.items.filter(rules::needsConfirmation)
        if (pending.isNotEmpty()) throw UnconfirmedItemsException(pending.map { it.id })
    }
}
