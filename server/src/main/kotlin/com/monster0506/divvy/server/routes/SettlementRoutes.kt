package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.Settlement
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.SettlementRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_SETTLEMENT_NOTE_LENGTH = 500

@Serializable
data class SettlementRequest(
    val fromPersonId: String? = null,
    val toPersonId: String? = null,
    val amountMinor: Long? = null,
    val note: String? = null,
)

@Serializable
data class SettlementResponse(
    val id: String,
    val householdId: String,
    val fromPersonId: String,
    val toPersonId: String,
    val amountMinor: Long,
    val currency: String,
    val settledAt: String,
    val note: String?,
    val createdAt: String,
)

private fun Settlement.toResponse() = SettlementResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    fromPersonId = fromPersonId.toString(),
    toPersonId = toPersonId.toString(),
    amountMinor = amount.amountMinor,
    currency = amount.currency.code,
    settledAt = settledAt.toString(),
    note = note,
    createdAt = createdAt.toString(),
)

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private fun invalid(message: String) = ApiError("validation_error", message)

private val householdNotFound = ApiError("not_found", "household not found")

@OptIn(ExperimentalUuidApi::class)
fun Route.settlementRoutes(
    households: () -> HouseholdRepository,
    people: () -> PersonRepository,
    settlements: () -> SettlementRepository,
) {
    route("/households/{id}/settlements") {
        post {
            val household = parseId(call.parameters["id"])?.let { households().findById(it) }
                ?: return@post call.respond(HttpStatusCode.NotFound, householdNotFound)
            val body = runCatching { call.receive<SettlementRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, invalid("invalid JSON body"))

            val fromId = parseId(body.fromPersonId)
                ?: return@post call.respond(HttpStatusCode.BadRequest, invalid("fromPersonId must be a valid id"))
            val toId = parseId(body.toPersonId)
                ?: return@post call.respond(HttpStatusCode.BadRequest, invalid("toPersonId must be a valid id"))
            if (fromId == toId) {
                return@post call.respond(HttpStatusCode.BadRequest, invalid("a person cannot settle with themselves"))
            }
            val amount = body.amountMinor?.takeIf { it > 0 }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    invalid("amountMinor must be a positive number of cents"),
                )
            val note = body.note?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null && note.length > MAX_SETTLEMENT_NOTE_LENGTH) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    invalid("note must be at most $MAX_SETTLEMENT_NOTE_LENGTH characters"),
                )
            }
            if (listOf(fromId, toId).any { people().findById(it)?.householdId != household.id }) {
                return@post call.respond(HttpStatusCode.BadRequest, invalid("both people must belong to the household"))
            }

            val recorded = settlements().record(
                NewSettlement(
                    householdId = household.id,
                    fromPersonId = fromId,
                    toPersonId = toId,
                    amount = Money(amount, household.defaultCurrency),
                    note = note,
                ),
            )
            call.respond(HttpStatusCode.Created, recorded.toResponse())
        }

        get {
            val householdId = parseId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@get call.respond(HttpStatusCode.NotFound, householdNotFound)
            call.respond(settlements().listByHousehold(householdId).map { it.toResponse() })
        }
    }
}
