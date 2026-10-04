package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlin.uuid.Uuid

/** How often the model's predictions were kept or changed in one household. */
data class MlStatsSummary(
    /** Items on confirmed receipts. */
    val labelledItems: Int,
    /** Items with no model prediction, which a person assigned. */
    val manual: Int,
    /** Items whose model prediction was kept as it was. */
    val predictedAccepted: Int,
    /** Items whose model prediction a person changed. */
    val predictedCorrected: Int,
    /** Share of predicted items that were changed, or null before there is any. */
    val correctionRate: Double?,
    /** Items the model has been asked about, including ones it did not know. */
    val predictionsStored: Int,
)

class MlStats(
    private val receipts: ReceiptRepository,
    private val predictions: PredictionRepository,
) {
    suspend fun forHousehold(householdId: Uuid): MlStatsSummary {
        val sources = receipts.listByHousehold(householdId, Int.MAX_VALUE)
            .filter { it.status == ReceiptStatus.CONFIRMED }
            .flatMap { it.items }
            .map { it.labelSource ?: LabelSource.MANUAL }
        return MlStatsSummary(
            labelledItems = sources.size,
            manual = sources.count { it == LabelSource.MANUAL },
            predictedAccepted = sources.count { it == LabelSource.PREDICTED_ACCEPTED },
            predictedCorrected = sources.count { it == LabelSource.PREDICTED_CORRECTED },
            correctionRate = receipts.correctionRate(householdId),
            predictionsStored = predictions.countForHousehold(householdId),
        )
    }
}
