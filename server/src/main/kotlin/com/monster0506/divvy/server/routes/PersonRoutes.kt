package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.PersonPatch
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.PersonRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_PERSON_NAME_LENGTH = 80
private const val UNIQUE_VIOLATION = "23505"

@Serializable
data class PersonRequest(val displayName: String? = null)

/** `PATCH`: omitted fields stay as they are. An empty [venmoUsername] removes it. */
@Serializable
data class PersonPatchRequest(val displayName: String? = null, val venmoUsername: String? = null)

@Serializable
data class PersonResponse(
    val id: String,
    val householdId: String,
    val displayName: String,
    val archivedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val venmoUsername: String? = null,
)

internal fun Person.toResponse() = PersonResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    displayName = displayName,
    archivedAt = archivedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    venmoUsername = venmoUsername,
)

/** Returns the trimmed name, or null if it is empty or longer than 80 characters. */
internal fun validPersonName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    return name.takeIf { it.isNotEmpty() && it.length <= MAX_PERSON_NAME_LENGTH }
}

private val venmoUsernamePattern = Regex("^[A-Za-z0-9_-]{5,30}$")

/**
 * Returns the username without spaces or a leading "@", "" for a blank value (which clears it), or
 * null if it is not 5 to 30 letters, digits, hyphens and underscores.
 */
internal fun validVenmoUsername(raw: String): String? {
    val username = raw.trim().removePrefix("@")
    return username.takeIf { it.isEmpty() || venmoUsernamePattern.matches(it) }
}

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val badJson = ApiError("validation_error", "invalid JSON body")
private val badName =
    ApiError("validation_error", "displayName must be between 1 and $MAX_PERSON_NAME_LENGTH characters")
private val badVenmo =
    ApiError("validation_error", "venmoUsername must be 5 to 30 letters, numbers, hyphens or underscores")
private val emptyPatch = ApiError("validation_error", "send displayName or venmoUsername")
private val duplicateName = ApiError("validation_error", "a person with that name already exists in this household")
private val householdNotFound = ApiError("not_found", "household not found")
private val personNotFound = ApiError("not_found", "person not found")

/** Repositories are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.personRoutes(households: () -> HouseholdRepository, people: () -> PersonRepository) {
    route("/households/{id}/people") {
        post {
            val householdId = parseId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@post call.respond(HttpStatusCode.NotFound, householdNotFound)
            val body = runCatching { call.receive<PersonRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validPersonName(body.displayName)
                ?: return@post call.respond(HttpStatusCode.BadRequest, badName)
            val created = try {
                people().add(householdId, name)
            } catch (e: ExposedSQLException) {
                // The unique index also catches two requests racing past each other.
                if (e.sqlState != UNIQUE_VIOLATION) throw e
                return@post call.respond(HttpStatusCode.BadRequest, duplicateName)
            }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        get {
            val householdId = parseId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@get call.respond(HttpStatusCode.NotFound, householdNotFound)
            val includeArchived = call.request.queryParameters["includeArchived"].toBoolean()
            call.respond(people().listByHousehold(householdId, includeArchived).map { it.toResponse() })
        }

        patch("/{personId}") {
            val householdId = parseId(call.parameters["id"])?.takeIf { households().findById(it) != null }
                ?: return@patch call.respond(HttpStatusCode.NotFound, householdNotFound)
            val personId = parseId(call.parameters["personId"])
                ?.takeIf { people().findById(it)?.householdId == householdId }
                ?: return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            val body = runCatching { call.receive<PersonPatchRequest>() }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
            if (body.displayName == null && body.venmoUsername == null) {
                return@patch call.respond(HttpStatusCode.BadRequest, emptyPatch)
            }
            val name = body.displayName?.let {
                validPersonName(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badName)
            }
            val venmo = body.venmoUsername?.let {
                validVenmoUsername(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badVenmo)
            }
            val updated = try {
                people().update(personId, PersonPatch(name, venmo))
            } catch (e: ExposedSQLException) {
                if (e.sqlState != UNIQUE_VIOLATION) throw e
                return@patch call.respond(HttpStatusCode.BadRequest, duplicateName)
            } ?: return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            call.respond(updated.toResponse())
        }
    }
}
