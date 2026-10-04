package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.ml.MlResult
import com.monster0506.divvy.server.ml.MlResync
import com.monster0506.divvy.server.ml.MlSyncStatus
import com.monster0506.divvy.server.repository.HouseholdRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MlStatusResponse(
    val databaseLabels: Int,
    val mlLabels: Int? = null,
    val inSync: Boolean? = null,
    val dirty: Boolean,
    val mlAvailable: Boolean,
    val mlReady: Boolean? = null,
    val modelVersion: String? = null,
)

@Serializable
data class MlResyncResponse(val sent: Int, val mlLabels: Int)

private fun MlSyncStatus.toResponse() = MlStatusResponse(
    databaseLabels = databaseLabels,
    mlLabels = mlLabels,
    inSync = inSync,
    dirty = dirty,
    mlAvailable = mlLabels != null,
    mlReady = mlReady,
    modelVersion = modelVersion,
)

private val householdMissing = ApiError("not_found", "household not found")
private val mlUnavailable = ApiError("ml_unavailable", "the ML service is not available right now")

private fun parseHouseholdId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

/**
 * Operator endpoints for the ML copy of a household: compare it with the database, or rebuild it.
 * Predictions repair it on their own; these are for checking and for forcing it.
 */
fun Route.mlRoutes(households: () -> HouseholdRepository, resync: () -> MlResync) {
    route("/households/{id}/ml") {
        get("/status") {
            val householdId = parseHouseholdId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@get call.respond(HttpStatusCode.NotFound, householdMissing)
            call.respond(resync().status(householdId).toResponse())
        }

        post("/resync") {
            val householdId = parseHouseholdId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@post call.respond(HttpStatusCode.NotFound, householdMissing)
            when (val result = resync().resync(householdId)) {
                is MlResult.Success ->
                    call.respond(MlResyncResponse(sent = result.value.received, mlLabels = result.value.historySize))
                is MlResult.Unavailable -> call.respond(HttpStatusCode.ServiceUnavailable, mlUnavailable)
                is MlResult.Rejected ->
                    call.respond(HttpStatusCode.BadGateway, ApiError("ml_rejected", result.message))
            }
        }
    }
}
