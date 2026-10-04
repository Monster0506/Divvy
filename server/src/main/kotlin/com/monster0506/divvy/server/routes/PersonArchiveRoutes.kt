package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.PersonRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private data class ArchivedPersonResponse(
    val id: String,
    val householdId: String,
    val name: String,
    val archivedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val householdNotFound = ApiError("not_found", "household not found")
private val personNotFound = ApiError("not_found", "person not found")

/** Archiving keeps the person's history; calling it again is harmless and returns the same person. */
@OptIn(ExperimentalUuidApi::class)
fun Route.personArchiveRoutes(households: () -> HouseholdRepository, people: () -> PersonRepository) {
    post("/households/{id}/people/{personId}/archive") {
        val household = parseId(call.parameters["id"])?.let { households().findById(it) }
            ?: return@post call.respond(HttpStatusCode.NotFound, householdNotFound)
        val person = parseId(call.parameters["personId"])?.let { people().findById(it) }
        if (person == null || person.householdId != household.id) {
            return@post call.respond(HttpStatusCode.NotFound, personNotFound)
        }
        val archived = people().archive(person.id)
            ?: return@post call.respond(HttpStatusCode.NotFound, personNotFound)
        call.respond(
            ArchivedPersonResponse(
                id = archived.id.toString(),
                householdId = archived.householdId.toString(),
                name = archived.displayName,
                archivedAt = archived.archivedAt?.toString(),
                createdAt = archived.createdAt.toString(),
                updatedAt = archived.updatedAt.toString(),
            ),
        )
    }
}