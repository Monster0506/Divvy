package com.monster0506.divvy.server.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire types for the ML service. They mirror `ml/openapi.json`: field names are snake_case there,
 * and ids are plain strings (the server passes UUIDs as text).
 */

@Serializable
data class MlPerson(val id: String, val name: String)

@Serializable
data class MlItem(
    @SerialName("item_id") val itemId: String,
    val description: String,
)

@Serializable
data class MlPredictRequest(
    @SerialName("household_id") val householdId: String,
    val people: List<MlPerson>,
    val items: List<MlItem>,
)

@Serializable
enum class MlPredictionStatus {
    @SerialName("predicted")
    PREDICTED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class MlAlternative(
    val owners: List<String>,
    val confidence: Double,
)

@Serializable
data class MlItemPrediction(
    @SerialName("item_id") val itemId: String,
    val status: MlPredictionStatus,
    val owners: List<String> = emptyList(),
    val confidence: Double,
    val alternatives: List<MlAlternative> = emptyList(),
)

@Serializable
data class MlPredictResponse(
    @SerialName("model_version") val modelVersion: String,
    val predictions: List<MlItemPrediction>,
)

/** How an observation's owners were decided, as the ML service names it. */
@Serializable
enum class MlAssignmentSource {
    @SerialName("manual")
    MANUAL,

    @SerialName("predicted_accepted")
    PREDICTED_ACCEPTED,

    @SerialName("predicted_corrected")
    PREDICTED_CORRECTED,
}

/** One confirmed item and who owns it. Sending the same [observationId] again updates it. */
@Serializable
data class MlObservation(
    @SerialName("observation_id") val observationId: String,
    /** ISO-8601 date, `YYYY-MM-DD`. */
    @SerialName("purchased_on") val purchasedOn: String,
    val description: String,
    /** Person ids who were in the household then. People missing here are unknown, not "no". */
    @SerialName("member_ids") val memberIds: List<String>,
    @SerialName("owner_ids") val ownerIds: List<String>,
    val source: MlAssignmentSource = MlAssignmentSource.MANUAL,
)

@Serializable
data class MlUpsertRequest(val observations: List<MlObservation>)

@Serializable
data class MlUpsertResponse(
    @SerialName("household_id") val householdId: String,
    val received: Int,
    @SerialName("history_size") val historySize: Int,
)

@Serializable
data class MlHistoryStatus(
    @SerialName("household_id") val householdId: String,
    @SerialName("history_size") val historySize: Int,
    @SerialName("min_history") val minHistory: Int,
    val ready: Boolean,
    @SerialName("model_version") val modelVersion: String? = null,
    /** Fingerprint of the stored history; compare with [historyDigest]. Absent on older ML services. */
    val digest: String? = null,
)
