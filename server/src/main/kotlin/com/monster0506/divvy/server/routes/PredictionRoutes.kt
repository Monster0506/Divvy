package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.ml.ItemPredictionView
import com.monster0506.divvy.server.ml.MlStats
import com.monster0506.divvy.server.ml.MlStatsSummary
import com.monster0506.divvy.server.ml.PredictionResult
import com.monster0506.divvy.server.ml.PredictionService
import com.monster0506.divvy.server.ml.ReceiptPredictions
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class AlternativeResponse(val ownerIds: List<String>, val confidence: Double)

@Serializable
data class ItemPredictionResponse(
    val itemId: String,
    /** `predicted`, `unknown`, `user_assigned` or `unavailable`. */
    val state: String,
    val ownerIds: List<String>,
    val confidence: Double? = null,
    val alternatives: List<AlternativeResponse> = emptyList(),
)

@Serializable
data class PredictionsResponse(
    val receiptId: String,
    val mlAvailable: Boolean,
    val modelVersion: String? = null,
    /** Present when predictions could not be made: tell the person to assign by hand. */
    val notice: String? = null,
    val items: List<ItemPredictionResponse>,
)

@Serializable
data class MlStatsResponse(
    val labelledItems: Int,
    val manual: Int,
    val predictedAccepted: Int,
    val predictedCorrected: Int,
    val correctionRate: Double? = null,
    val predictionsStored: Int,
)

private fun ItemPredictionView.toResponse() = ItemPredictionResponse(
    itemId = itemId.toString(),
    state = state.name.lowercase(),
    ownerIds = ownerIds.map { it.toString() },
    confidence = confidence,
    alternatives = alternatives.map { AlternativeResponse(it.ownerIds.map(Uuid::toString), it.confidence) },
)

private fun ReceiptPredictions.toResponse() = PredictionsResponse(
    receiptId = receiptId.toString(),
    mlAvailable = mlAvailable,
    modelVersion = modelVersion,
    notice = notice,
    items = items.map { it.toResponse() },
)

private fun MlStatsSummary.toResponse() = MlStatsResponse(
    labelledItems, manual, predictedAccepted, predictedCorrected, correctionRate, predictionsStored,
)

private val receiptNotFound = ApiError("not_found", "receipt not found")
private val householdNotFound = ApiError("not_found", "household not found")
private val notDraft = ApiError("not_draft", "only a draft receipt can be pre-assigned")

private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

/** The receipt id from the path, if the receipt exists and belongs to the household in the path. */
private suspend fun RoutingContext.receiptIdInHousehold(receipts: ReceiptRepository): Uuid? {
    val householdId = parseId(call.parameters["id"]) ?: return null
    val receiptId = parseId(call.parameters["receiptId"]) ?: return null
    return receiptId.takeIf { receipts.findById(it)?.householdId == householdId }
}

/**
 * Pre-assigning a draft receipt's items from the household's history. POST asks the model now and stores the
 * answer; GET returns what was stored. When the ML service cannot be used the response says so and nothing changes.
 */
fun Route.predictionRoutes(receipts: () -> ReceiptRepository, service: () -> PredictionService) {
    route("/households/{id}/receipts/{receiptId}/predictions") {
        post {
            val receiptId = receiptIdInHousehold(receipts()) ?: return@post call.respond(HttpStatusCode.NotFound, receiptNotFound)
            when (val result = service().predictFor(receiptId)) {
                PredictionResult.NotFound -> call.respond(HttpStatusCode.NotFound, receiptNotFound)
                PredictionResult.NotDraft -> call.respond(HttpStatusCode.Conflict, notDraft)
                is PredictionResult.Done -> call.respond(result.predictions.toResponse())
            }
        }

        get {
            val receiptId = receiptIdInHousehold(receipts()) ?: return@get call.respond(HttpStatusCode.NotFound, receiptNotFound)
            when (val result = service().stored(receiptId)) {
                PredictionResult.NotFound, PredictionResult.NotDraft -> call.respond(HttpStatusCode.NotFound, receiptNotFound)
                is PredictionResult.Done -> call.respond(result.predictions.toResponse())
            }
        }
    }
}

/** How often predictions were kept or changed in a household. */
fun Route.mlStatsRoutes(households: () -> HouseholdRepository, stats: () -> MlStats) {
    get("/households/{id}/ml/stats") {
        val householdId = parseId(call.parameters["id"])?.takeIf { households().findById(it) != null }
            ?: return@get call.respond(HttpStatusCode.NotFound, householdNotFound)
        call.respond(stats().forHousehold(householdId).toResponse())
    }
}
