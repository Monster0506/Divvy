package com.monster0506.divvy.server.ml

import java.time.Instant
import kotlin.uuid.Uuid

/** Another owner set the model considered for an item. */
data class StoredAlternative(val ownerIds: List<Uuid>, val confidence: Double)

/** What the model said about one receipt item, whether or not anyone agreed. */
data class StoredPrediction(
    val receiptItemId: Uuid,
    val householdId: Uuid,
    val modelVersion: String,
    val status: MlPredictionStatus,
    /** Probability the predicted owner set is right, in [0, 1]; 0 for an unknown item. */
    val confidence: Double,
    val alternatives: List<StoredAlternative>,
    val predictedAt: Instant,
)

interface PredictionRepository {
    /** Stores predictions, replacing any earlier one for the same item. */
    suspend fun save(predictions: List<StoredPrediction>)

    /** The predictions stored for a receipt's items, in no particular order. */
    suspend fun forReceipt(receiptId: Uuid): List<StoredPrediction>

    /** How many items in the household have a stored prediction. */
    suspend fun countForHousehold(householdId: Uuid): Int
}
