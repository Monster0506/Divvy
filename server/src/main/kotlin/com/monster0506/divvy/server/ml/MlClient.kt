package com.monster0506.divvy.server.ml

/**
 * The backend's view of the ML service. Every call returns an [MlResult]: the ML service being
 * slow, down or broken is an expected situation, so it is a value and never an exception. A
 * prediction or a label push that fails must not fail the request that triggered it.
 */
interface MlClient {
    /** Predicts owners for [request]'s items from the household's stored history. */
    suspend fun predict(request: MlPredictRequest): MlResult<MlPredictResponse>

    /**
     * Adds or updates confirmed observations by id. With [replace] the household's whole history is
     * swapped for [observations], which is how the ML copy is rebuilt from the database.
     */
    suspend fun upsertObservations(
        householdId: String,
        observations: List<MlObservation>,
        replace: Boolean = false,
    ): MlResult<MlUpsertResponse>

    suspend fun deleteObservation(householdId: String, observationId: String): MlResult<Unit>

    /** How many observations the ML service holds for the household, and whether that is enough to predict. */
    suspend fun history(householdId: String): MlResult<MlHistoryStatus>
}

sealed interface MlResult<out T> {
    data class Success<T>(val value: T) : MlResult<T>

    /** Timeout, connection failure, a 5xx, or a response that could not be read. Worth retrying later. */
    data class Unavailable(val reason: String) : MlResult<Nothing>

    /** The ML service understood the request and refused it (4xx). Retrying the same request will not help. */
    data class Rejected(val status: Int, val message: String) : MlResult<Nothing>
}

/** The value on success, or null for [MlResult.Unavailable] and [MlResult.Rejected]. */
fun <T> MlResult<T>.valueOrNull(): T? = (this as? MlResult.Success<T>)?.value

/** Used when `ML_SERVICE_URL` is not set: every call reports the service as unavailable. */
object NoopMlClient : MlClient {
    private const val REASON = "ML service is not configured"

    override suspend fun predict(request: MlPredictRequest) = MlResult.Unavailable(REASON)

    override suspend fun upsertObservations(
        householdId: String,
        observations: List<MlObservation>,
        replace: Boolean,
    ) = MlResult.Unavailable(REASON)

    override suspend fun deleteObservation(householdId: String, observationId: String) =
        MlResult.Unavailable(REASON)

    override suspend fun history(householdId: String) = MlResult.Unavailable(REASON)
}
