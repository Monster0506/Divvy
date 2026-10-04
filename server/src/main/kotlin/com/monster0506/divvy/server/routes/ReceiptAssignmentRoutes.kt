package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ConfirmResult
import com.monster0506.divvy.server.repository.ItemOwnerRepository
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class OwnerRequest(val personId: String? = null, val weight: Int = 1)

@Serializable
data class SetOwnersRequest(val owners: List<OwnerRequest>? = null)

@Serializable
data class OwnerResponse(val personId: String, val weight: Int, val source: String)

@Serializable
data class ItemOwnersResponse(val itemId: String, val owners: List<OwnerResponse>)

@Serializable
data class ConfirmedItemResponse(val id: String, val owners: List<OwnerResponse>)

@Serializable
data class ConfirmedReceiptResponse(
    val id: String,
    val status: String,
    val confirmedAt: String?,
    val items: List<ConfirmedItemResponse>,
)

private fun ItemOwner.toResponse() = OwnerResponse(personId.toString(), shareWeight, source.dbValue)

private fun Receipt.toConfirmedResponse() = ConfirmedReceiptResponse(
    id = id.toString(),
    status = status.dbValue,
    confirmedAt = confirmedAt?.toString(),
    items = items.map { item -> ConfirmedItemResponse(item.id.toString(), item.owners.map { it.toResponse() }) },
)

@OptIn(ExperimentalUuidApi::class)
private fun parseUuid(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val receiptNotFound = ApiError("not_found", "receipt not found")
private val itemNotFound = ApiError("not_found", "item not found")

private suspend fun RoutingCall.invalid(message: String, details: List<String> = emptyList()) =
    respond(HttpStatusCode.BadRequest, ApiError("validation_error", message, details))

/** Repositories are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.receiptAssignmentRoutes(
    receipts: () -> ReceiptRepository,
    itemOwners: () -> ItemOwnerRepository,
    people: () -> PersonRepository,
) {
    route("/receipts/{id}") {
        put("/items/{itemId}/owners") {
            val receipt = parseUuid(call.parameters["id"])?.let { receipts().findById(it) }
                ?: return@put call.respond(HttpStatusCode.NotFound, receiptNotFound)
            val item = parseUuid(call.parameters["itemId"])?.let { id -> receipt.items.find { it.id == id } }
                ?: return@put call.respond(HttpStatusCode.NotFound, itemNotFound)
            val requested = runCatching { call.receive<SetOwnersRequest>() }.getOrNull()?.owners
                ?: return@put call.invalid("owners is required")
            if (receipt.status != ReceiptStatus.DRAFT) {
                return@put call.respond(HttpStatusCode.Conflict, ApiError("conflict", "receipt is ${receipt.status.dbValue}"))
            }

            val shares = requested.map { owner ->
                val personId = parseUuid(owner.personId) ?: return@put call.invalid("each owner needs a valid personId")
                if (owner.weight < 1) return@put call.invalid("weight must be at least 1")
                OwnerShare(personId, owner.weight, OwnershipSource.USER)
            }
            if (shares.map { it.personId }.toSet().size != shares.size) {
                return@put call.invalid("a person can only be listed once")
            }
            val unusable = shares.filter { share ->
                people().findById(share.personId)
                    ?.let { it.householdId != receipt.householdId || it.archivedAt != null } != false
            }
            if (unusable.isNotEmpty()) {
                return@put call.invalid(
                    "owners must be active members of the household",
                    unusable.map { it.personId.toString() },
                )
            }

            val saved = itemOwners().setOwners(item.id, shares)
            call.respond(ItemOwnersResponse(item.id.toString(), saved.map { it.toResponse() }))
        }

        post("/confirm") {
            val id = parseUuid(call.parameters["id"])
                ?: return@post call.respond(HttpStatusCode.NotFound, receiptNotFound)
            when (val result = receipts().confirmAssigned(id)) {
                ConfirmResult.NotFound -> call.respond(HttpStatusCode.NotFound, receiptNotFound)
                is ConfirmResult.NotDraft -> call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("conflict", "receipt is ${result.status.dbValue}"),
                )
                is ConfirmResult.Unassigned -> call.invalid(
                    "every item needs at least one active owner",
                    result.itemIds.map { it.toString() },
                )
                is ConfirmResult.Confirmed -> call.respond(result.receipt.toConfirmedResponse())
            }
        }
    }
}
