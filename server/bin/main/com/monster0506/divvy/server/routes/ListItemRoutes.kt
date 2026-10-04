package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ListItem
import com.monster0506.divvy.server.domain.ListItemPatch
import com.monster0506.divvy.server.domain.NewListItem
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ListItemRepository
import com.monster0506.divvy.server.repository.PersonRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_LIST_ITEM_TEXT = 200
const val MAX_LIST_ITEM_QUANTITY = 50
const val MAX_LIST_ITEM_NOTES = 1000
private const val DEFAULT_AUTOCOMPLETE_LIMIT = 10
private const val MAX_AUTOCOMPLETE_LIMIT = 50

/** Fields are nullable so a missing one is reported as a 400 instead of a decoding failure. */
@Serializable
data class CreateListItemRequest(
    val text: String? = null,
    val quantity: String? = null,
    val notes: String? = null,
    val addedByPersonId: String? = null,
)

/** Absent fields are unchanged. An empty `quantity` or `notes` clears it. */
@Serializable
data class UpdateListItemRequest(
    val text: String? = null,
    val quantity: String? = null,
    val notes: String? = null,
    val purchased: Boolean? = null,
)

@Serializable
data class ListItemResponse(
    val id: String,
    val householdId: String,
    val text: String,
    val quantity: String? = null,
    val notes: String? = null,
    val addedByPersonId: String? = null,
    /** The adder's current name, so the app can show it without loading the members. */
    val addedByName: String? = null,
    /** `open` or `purchased`. */
    val status: String,
    val purchasedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AutocompleteResponse(val suggestions: List<String>)

private fun ListItem.toResponse(names: Map<Uuid, String>) = ListItemResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    text = text,
    quantity = quantity,
    notes = notes,
    addedByPersonId = addedByPersonId?.toString(),
    addedByName = addedByPersonId?.let(names::get),
    status = status.dbValue,
    purchasedAt = purchasedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.findHousehold(households: HouseholdRepository): Household? =
    runCatching { Uuid.parse(parameters["id"].orEmpty()) }.getOrNull()?.let { households.findById(it) }

@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.findItem(household: Household, items: ListItemRepository): ListItem? =
    runCatching { Uuid.parse(parameters["itemId"].orEmpty()) }.getOrNull()
        ?.let { items.findById(it) }
        ?.takeIf { it.householdId == household.id }

private suspend fun ApplicationCall.notFound(what: String) =
    respond(HttpStatusCode.NotFound, ApiError("not_found", "$what not found"))

private suspend fun ApplicationCall.validationError(message: String, details: List<String> = emptyList()) =
    respond(HttpStatusCode.BadRequest, ApiError("validation_error", message, details))

/** Blank optional text becomes null; anything else is trimmed. */
private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun MutableList<String>.checkLength(field: String, value: String?, max: Int) {
    if (value != null && value.length > max) add("$field must be at most $max characters")
}

/**
 * The household's shared grocery list. Dependencies are resolved per request because the database
 * connects after routing is set up.
 */
@OptIn(ExperimentalUuidApi::class)
fun Route.listItemRoutes(
    households: () -> HouseholdRepository,
    people: () -> PersonRepository,
    items: () -> ListItemRepository,
) {
    suspend fun namesIn(household: Household): Map<Uuid, String> =
        people().listByHousehold(household.id, includeArchived = true).associate { it.id to it.displayName }

    route("/households/{id}/list") {
        get {
            val household = call.findHousehold(households()) ?: return@get call.notFound("household")
            val names = namesIn(household)
            call.respond(items().listByHousehold(household.id).map { it.toResponse(names) })
        }

        post {
            val household = call.findHousehold(households()) ?: return@post call.notFound("household")
            val body = runCatching { call.receive<CreateListItemRequest>() }.getOrNull()
                ?: return@post call.validationError("expected a JSON body with text")

            val text = body.text.cleaned()
            val quantity = body.quantity.cleaned()
            val notes = body.notes.cleaned()
            val addedBy = body.addedByPersonId?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
            val errors = buildList {
                if (text == null) add("text must not be blank")
                checkLength("text", text, MAX_LIST_ITEM_TEXT)
                checkLength("quantity", quantity, MAX_LIST_ITEM_QUANTITY)
                checkLength("notes", notes, MAX_LIST_ITEM_NOTES)
                if (body.addedByPersonId != null && addedBy == null) add("addedByPersonId must be a valid id")
            }
            if (errors.isNotEmpty()) return@post call.validationError("invalid list item", errors)

            if (addedBy != null) {
                val person = people().findById(addedBy)
                if (person?.householdId != household.id || person.archivedAt != null) {
                    return@post call.validationError(
                        "invalid list item",
                        listOf("addedByPersonId is not a member of this household"),
                    )
                }
            }

            val item = items().add(NewListItem(household.id, text!!, quantity, notes, addedBy))
            call.respond(HttpStatusCode.Created, item.toResponse(namesIn(household)))
        }

        // Declared before /{itemId} for readability; Ktor prefers the constant segment either way.
        get("/autocomplete") {
            val household = call.findHousehold(households()) ?: return@get call.notFound("household")
            val limitParam = call.request.queryParameters["limit"]
            val limit = if (limitParam == null) DEFAULT_AUTOCOMPLETE_LIMIT else limitParam.toIntOrNull()
            if (limit == null || limit !in 1..MAX_AUTOCOMPLETE_LIMIT) {
                return@get call.validationError("limit must be between 1 and $MAX_AUTOCOMPLETE_LIMIT")
            }
            val query = call.request.queryParameters["q"].orEmpty()
            call.respond(AutocompleteResponse(items().autocomplete(household.id, query, limit)))
        }

        get("/{itemId}") {
            val household = call.findHousehold(households()) ?: return@get call.notFound("household")
            val item = call.findItem(household, items()) ?: return@get call.notFound("list item")
            call.respond(item.toResponse(namesIn(household)))
        }

        patch("/{itemId}") {
            val household = call.findHousehold(households()) ?: return@patch call.notFound("household")
            val item = call.findItem(household, items()) ?: return@patch call.notFound("list item")
            val body = runCatching { call.receive<UpdateListItemRequest>() }.getOrNull()
                ?: return@patch call.validationError("expected a JSON body")

            // Optional fields keep "" to mean "clear it"; the repository stores that as null.
            val text = body.text?.trim()
            val quantity = body.quantity?.trim()
            val notes = body.notes?.trim()
            val errors = buildList {
                if (text != null && text.isEmpty()) add("text must not be blank")
                checkLength("text", text, MAX_LIST_ITEM_TEXT)
                checkLength("quantity", quantity, MAX_LIST_ITEM_QUANTITY)
                checkLength("notes", notes, MAX_LIST_ITEM_NOTES)
            }
            if (errors.isNotEmpty()) return@patch call.validationError("invalid list item", errors)

            val updated = items().update(item.id, ListItemPatch(text, quantity, notes, body.purchased))
                ?: return@patch call.notFound("list item")
            call.respond(updated.toResponse(namesIn(household)))
        }

        delete("/{itemId}") {
            val household = call.findHousehold(households()) ?: return@delete call.notFound("household")
            val item = call.findItem(household, items()) ?: return@delete call.notFound("list item")
            if (!items().delete(item.id)) return@delete call.notFound("list item")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
