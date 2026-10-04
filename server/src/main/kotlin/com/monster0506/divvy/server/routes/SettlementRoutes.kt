package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.Settlement
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.SettlementRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Fields are nullable so a missing one is reported as a 400 instead of a decoding failure. */
@Serializable
data class CreateSettlementRequest(
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
    val note: String? = null,
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
private fun String?.toUuidOrNull(): Uuid? = this?.let { runCatching { Uuid.parse(it) }.getOrNull() }

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.findHousehold(households: HouseholdRepository): Household? =
    runCatching { Uuid.parse(parameters["id"].orEmpty()) }.getOrNull()?.let { households.findById(it) }

private suspend fun ApplicationCall.validationError(message: String, details: List<String> = emptyList()) =
    respond(HttpStatusCode.BadRequest, ApiError("validation_error", message, details))

/** Dependencies are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.settlementRoutes(
    households: () -> HouseholdRepository,
    people: () -> PersonRepository,
    settlements: () -> SettlementRepository,
) {
    route("/households/{id}/settlements") {
        post {
            val household = call.findHousehold(households())
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))

            val body = runCatching { call.receive<CreateSettlementRequest>() }.getOrNull()
                ?: return@post call.validationError(
                    "expected a JSON body with fromPersonId, toPersonId and amountMinor",
                )

            val from = body.fromPersonId.toUuidOrNull()
            val to = body.toPersonId.toUuidOrNull()
            val amount = body.amountMinor
            val note = body.note?.trim()?.takeIf { it.isNotEmpty() }

            val errors = buildList {
                if (from == null) add("fromPersonId must be a valid id")
                if (to == null) add("toPersonId must be a valid id")
                if (amount == null || amount <= 0) add("amountMinor must be a positive integer")
                if (from != null && from == to) add("fromPersonId and toPersonId must be different people")
            }
            if (errors.isNotEmpty()) return@post call.validationError("invalid settlement", errors)

            // Both people are non-null here: any null was reported above.
            val fromId = from!!
            val toId = to!!

            val notMembers = listOf("fromPersonId" to fromId, "toPersonId" to toId)
                .filter { (_, id) -> people().findById(id)?.householdId != household.id }
                .map { (field, _) -> "$field is not a member of this household" }
            if (notMembers.isNotEmpty()) return@post call.validationError("invalid settlement", notMembers)

            val settlement = settlements().record(
                NewSettlement(
                    householdId = household.id,
                    fromPersonId = fromId,
                    toPersonId = toId,
                    amount = Money(amount!!, household.defaultCurrency),
                    note = note,
                ),
            )
            call.respond(HttpStatusCode.Created, settlement.toResponse())
        }

        get {
            val household = call.findHousehold(households())
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))
            call.respond(settlements().listByHousehold(household.id).map { it.toResponse() })
        }
    }
}
