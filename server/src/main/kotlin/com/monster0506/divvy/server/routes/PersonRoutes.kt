package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Household
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
import java.sql.SQLException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_PERSON_NAME_LENGTH = 80
private const val UNIQUE_VIOLATION = "23505"

@Serializable
data class PersonRequest(val name: String? = null)

@Serializable
data class PersonResponse(
    val id: String,
    val householdId: String,
    val name: String,
    val archivedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

private fun Person.toResponse() = PersonResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    name = displayName,
    archivedAt = archivedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun validPersonName(raw: String?): String? {
    val name = raw?.trim() ?: return null
    return name.takeIf { it.isNotEmpty() && it.length <= MAX_PERSON_NAME_LENGTH }
}

private fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == UNIQUE_VIOLATION }

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val badJson = ApiError("validation_error", "invalid JSON body")
private val badName =
    ApiError("validation_error", "name must be between 1 and $MAX_PERSON_NAME_LENGTH characters")
private val duplicateName =
    ApiError("validation_error", "a person with that name already exists in this household")
private val householdNotFound = ApiError("not_found", "household not found")
private val personNotFound = ApiError("not_found", "person not found")

@OptIn(ExperimentalUuidApi::class)
private suspend fun findHousehold(raw: String?, households: () -> HouseholdRepository): Household? =
    parseId(raw)?.let { households().findById(it) }

/** Repositories are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.personRoutes(households: () -> HouseholdRepository, people: () -> PersonRepository) {
    route("/households/{id}/people") {
        post {
            val household = findHousehold(call.parameters["id"], households)
                ?: return@post call.respond(HttpStatusCode.NotFound, householdNotFound)
            val body = runCatching { call.receive<PersonRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validPersonName(body.name)
                ?: return@post call.respond(HttpStatusCode.BadRequest, badName)
            val created = try {
                people().add(household.id, name)
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) return@post call.respond(HttpStatusCode.BadRequest, duplicateName)
                throw e
            }
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        get {
            val household = findHousehold(call.parameters["id"], households)
                ?: return@get call.respond(HttpStatusCode.NotFound, householdNotFound)
            val includeArchived = call.request.queryParameters["includeArchived"].toBoolean()
            call.respond(people().listByHousehold(household.id, includeArchived).map { it.toResponse() })
        }

        patch("/{personId}") {
            val household = findHousehold(call.parameters["id"], households)
                ?: return@patch call.respond(HttpStatusCode.NotFound, householdNotFound)
            val person = parseId(call.parameters["personId"])?.let { people().findById(it) }
            if (person == null || person.householdId != household.id) {
                return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            }
            val body = runCatching { call.receive<PersonRequest>() }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
            val name = validPersonName(body.name)
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badName)
            val updated = try {
                people().rename(person.id, name)
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) return@patch call.respond(HttpStatusCode.BadRequest, duplicateName)
                throw e
            } ?: return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            call.respond(updated.toResponse())
        }
    }
}