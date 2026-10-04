package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.ReceiptItem
import kotlin.uuid.Uuid

/** How a household wants model predictions used. */
data class MlSettings(
    /** When off, every prediction is only a suggestion that a person must confirm. */
    val autoAssign: Boolean = true,
    /** Confidence in [0, 1] at or above which a prediction is applied without confirmation. */
    val autoAssignThreshold: Double = DEFAULT_THRESHOLD,
) {
    /** True when the item's owners come only from the model and are not sure enough to apply unconfirmed. */
    fun needsConfirmation(item: ReceiptItem): Boolean {
        if (item.owners.isEmpty() || item.owners.any { it.source == OwnershipSource.USER }) return false
        if (!autoAssign) return true
        val confidence = item.owners.mapNotNull { it.confidence?.toDouble() }.minOrNull() ?: return true
        return confidence < autoAssignThreshold
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.85
    }
}

interface MlSettingsRepository {
    /** The household's settings, or the defaults when it has never changed them. */
    suspend fun get(householdId: Uuid): MlSettings

    suspend fun save(householdId: Uuid, settings: MlSettings): MlSettings
}
