package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Person
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

@Serializable
data class PersonResponse(
    val id: String,
    val householdId: String,
    val displayName: String,
    val archivedAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

internal fun Person.toResponse() = PersonResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    displayName = displayName,
    archivedAt = archivedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** Returns the trimmed name, or null if it is empty or longer than 80 characters. */
internal fun validPersonName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    return name.takeIf { it.isNotEmpty() && it.length <= MAX_PERSON_NAME_LENGTH }
}

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val badJson = ApiError("validation_error", "invalid JSON body")
private val badName =
    ApiError("validation_error", "displayName must be between 1 and $MAX_PERSON_NAME_LENGTH characters")
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
            val body = runCatching { call.receive<PersonRequest>() }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validPersonName(body.displayName)
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badName)
            val updated = try {
                people().rename(personId, name)
            } catch (e: ExposedSQLException) {
                if (e.sqlState != UNIQUE_VIOLATION) throw e
                return@patch call.respond(HttpStatusCode.BadRequest, duplicateName)
            } ?: return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            call.respond(updated.toResponse())
        }
    }
}
