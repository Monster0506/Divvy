package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.repository.HouseholdRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_HOUSEHOLD_NAME_LENGTH = 80
private const val DEFAULT_CURRENCY = "USD"
private const val NAME_ERROR = "name must be between 1 and $MAX_HOUSEHOLD_NAME_LENGTH characters"

@Serializable
data class CreateHouseholdRequest(val name: String? = null, val defaultCurrency: String? = null)

@Serializable
data class UpdateHouseholdRequest(val name: String? = null)

@Serializable
data class HouseholdResponse(
    val id: String,
    val name: String,
    val defaultCurrency: String,
    val createdAt: String,
    val updatedAt: String,
)

private fun Household.toResponse() = HouseholdResponse(
    id = id.toString(),
    name = name,
    defaultCurrency = defaultCurrency.code,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** Returns the trimmed name, or null if it is empty or longer than 80 characters. */
internal fun validHouseholdName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    return name.takeIf { it.isNotEmpty() && it.length <= MAX_HOUSEHOLD_NAME_LENGTH }
}

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val badJson = ApiError("validation_error", "invalid JSON body")
private val badName = ApiError("validation_error", NAME_ERROR)
private val notFound = ApiError("not_found", "household not found")

/** [households] is resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.householdRoutes(households: () -> HouseholdRepository) {
    route("/households") {
        post {
            val body = runCatching { call.receive<CreateHouseholdRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validHouseholdName(body.name)
                ?: return@post call.respond(HttpStatusCode.BadRequest, badName)
            val currency = runCatching { CurrencyCode((body.defaultCurrency ?: DEFAULT_CURRENCY).uppercase()) }
                .getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("validation_error", "defaultCurrency must be a 3-letter currency code"),
                )
            call.respond(HttpStatusCode.Created, households().create(name, currency).toResponse())
        }

        get("/{id}") {
            val household = parseId(call.parameters["id"])?.let { households().findById(it) }
                ?: return@get call.respond(HttpStatusCode.NotFound, notFound)
            call.respond(household.toResponse())
        }

        patch("/{id}") {
            val id = parseId(call.parameters["id"])
                ?: return@patch call.respond(HttpStatusCode.NotFound, notFound)
            val body = runCatching { call.receive<UpdateHouseholdRequest>() }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validHouseholdName(body.name)
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badName)
            val updated = households().rename(id, name)
                ?: return@patch call.respond(HttpStatusCode.NotFound, notFound)
            call.respond(updated.toResponse())
        }
    }
}