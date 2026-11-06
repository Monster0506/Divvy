package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.AVATAR_COLORS
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.PersonPatch
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.storage.ImageStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val MAX_PERSON_NAME_LENGTH = 80
const val MAX_PERSON_EMOJI_LENGTH = 16
const val MAX_PERSON_PHOTO_BYTES: Long = 2L * 1024 * 1024
private const val UNIQUE_VIOLATION = "23505"

@Serializable
data class PersonRequest(val displayName: String? = null)

/**
 * `PATCH`: omitted fields stay as they are. An empty [venmoUsername], [shortName], [emoji] or
 * [color] removes it.
 */
@Serializable
data class PersonPatchRequest(
    val displayName: String? = null,
    val venmoUsername: String? = null,
    val shortName: String? = null,
    val emoji: String? = null,
    val color: String? = null,
)

@Serializable
data class PersonResponse(
    val id: String,
    val householdId: String,
    val displayName: String,
    val archivedAt: String?,
    val createdAt: String,
    val updatedAt: String,
    val venmoUsername: String? = null,
    val shortName: String? = null,
    val emoji: String? = null,
    val color: String? = null,
    /** When the photo was last uploaded; present only if the person has one. */
    val photoUpdatedAt: String? = null,
)

internal fun Person.toResponse() = PersonResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    displayName = displayName,
    archivedAt = archivedAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    venmoUsername = venmoUsername,
    shortName = shortName,
    emoji = emoji,
    color = color,
    photoUpdatedAt = photoUpdatedAt?.toString(),
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

/** Returns "" (which clears it), the trimmed value if it is 1 or 2 characters, or null otherwise. */
internal fun validShortName(raw: String): String? {
    if (raw.isEmpty()) return ""
    return raw.trim().takeIf { it.length in 1..2 }
}

/** Returns "" (which clears it), the emoji if it is 1 to 16 characters and not blank, or null otherwise. */
internal fun validEmoji(raw: String): String? {
    if (raw.isEmpty()) return ""
    return raw.takeIf { it.isNotBlank() && it.length <= MAX_PERSON_EMOJI_LENGTH }
}

/** Returns "" (which clears it), the color if it is one of [AVATAR_COLORS], or null otherwise. */
internal fun validAvatarColor(raw: String): String? = raw.takeIf { it.isEmpty() || it in AVATAR_COLORS }

@OptIn(ExperimentalUuidApi::class)
private fun parseId(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private val badJson = ApiError("validation_error", "invalid JSON body")
private val badName =
    ApiError("validation_error", "displayName must be between 1 and $MAX_PERSON_NAME_LENGTH characters")
private val badVenmo =
    ApiError("validation_error", "venmoUsername must be 5 to 30 letters, numbers, hyphens or underscores")
private val badShortName = ApiError("validation_error", "shortName must be 1 or 2 characters")
private val badEmoji = ApiError("validation_error", "emoji must be 1 to $MAX_PERSON_EMOJI_LENGTH characters")
private val badColor = ApiError("validation_error", "color must be one of ${AVATAR_COLORS.joinToString()}")
private val emptyPatch = ApiError("validation_error", "send at least one field to change")
private val duplicateName = ApiError("validation_error", "a person with that name already exists in this household")
private val householdNotFound = ApiError("not_found", "household not found")
private val personNotFound = ApiError("not_found", "person not found")
private val photoNotFound = ApiError("not_found", "person photo not found")
private val notMultipart = ApiError("validation_error", "expected a multipart body with an image part")
private val missingPhoto = ApiError("validation_error", "missing multipart file part named image")
private val photoTooLarge =
    ApiError("payload_too_large", "photo must be at most $MAX_PERSON_PHOTO_BYTES bytes")
private val badPhotoType = ApiError("unsupported_media_type", "photo must be JPEG or PNG")

/** The person named by the `id` and `personId` path parameters, or null after answering 404. */
@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.findPersonOrRespond(
    households: HouseholdRepository,
    people: PersonRepository,
): Person? {
    val householdId = parseId(parameters["id"])?.takeIf { households.findById(it) != null }
    if (householdId == null) {
        respond(HttpStatusCode.NotFound, householdNotFound)
        return null
    }
    val person = parseId(parameters["personId"])?.let { people.findById(it) }?.takeIf { it.householdId == householdId }
    if (person == null) respond(HttpStatusCode.NotFound, personNotFound)
    return person
}

/**
 * Reads the multipart part named `image`, at most [MAX_PERSON_PHOTO_BYTES] of it. Returns the bytes,
 * or null after answering 400 or 413.
 */
private suspend fun ApplicationCall.receivePhotoOrRespond(): ByteArray? {
    val multipart = runCatching { receiveMultipart() }.getOrNull()
    if (multipart == null) {
        respond(HttpStatusCode.BadRequest, notMultipart)
        return null
    }
    var image: ByteArray? = null
    var tooLarge = false
    multipart.forEachPart { part ->
        if (part is PartData.FileItem && part.name == "image" && image == null && !tooLarge) {
            // Read one byte past the limit so an oversize file is detected without buffering all of it.
            val bytes = part.provider().readRemaining(MAX_PERSON_PHOTO_BYTES + 1).readByteArray()
            if (bytes.size > MAX_PERSON_PHOTO_BYTES) tooLarge = true else image = bytes
        }
        part.dispose()
    }
    if (tooLarge) {
        respond(HttpStatusCode.PayloadTooLarge, photoTooLarge)
        return null
    }
    if (image == null) respond(HttpStatusCode.BadRequest, missingPhoto)
    return image
}

/** Dependencies are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.personRoutes(
    households: () -> HouseholdRepository,
    people: () -> PersonRepository,
    storage: () -> ImageStorage,
) {
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
            if (body.displayName == null && body.venmoUsername == null && body.shortName == null &&
                body.emoji == null && body.color == null
            ) {
                return@patch call.respond(HttpStatusCode.BadRequest, emptyPatch)
            }
            val name = body.displayName?.let {
                validPersonName(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badName)
            }
            val venmo = body.venmoUsername?.let {
                validVenmoUsername(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badVenmo)
            }
            val shortName = body.shortName?.let {
                validShortName(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badShortName)
            }
            val emoji = body.emoji?.let {
                validEmoji(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badEmoji)
            }
            val color = body.color?.let {
                validAvatarColor(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badColor)
            }
            val updated = try {
                people().update(personId, PersonPatch(name, venmo, shortName, emoji, color))
            } catch (e: ExposedSQLException) {
                if (e.sqlState != UNIQUE_VIOLATION) throw e
                return@patch call.respond(HttpStatusCode.BadRequest, duplicateName)
            } ?: return@patch call.respond(HttpStatusCode.NotFound, personNotFound)
            call.respond(updated.toResponse())
        }

        route("/{personId}/photo") {
            put {
                val person = call.findPersonOrRespond(households(), people()) ?: return@put
                val bytes = call.receivePhotoOrRespond() ?: return@put
                val type = detectImageType(bytes)
                if (type != ImageType.JPEG && type != ImageType.PNG) {
                    return@put call.respond(HttpStatusCode.UnsupportedMediaType, badPhotoType)
                }
                val key = storage().put(bytes)
                val updated = try {
                    people().setPhoto(person.id, key)
                } catch (e: Exception) {
                    storage().delete(key)
                    throw e
                }
                if (updated == null) {
                    storage().delete(key)
                    return@put call.respond(HttpStatusCode.NotFound, personNotFound)
                }
                person.photoKey?.let { storage().delete(it) }
                call.respond(updated.toResponse())
            }

            get {
                val person = call.findPersonOrRespond(households(), people()) ?: return@get
                val bytes = person.photoKey?.let { storage().get(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound, photoNotFound)
                val type = when (detectImageType(bytes)) {
                    ImageType.JPEG -> ContentType.Image.JPEG
                    ImageType.PNG -> ContentType.Image.PNG
                    else -> ContentType.Application.OctetStream
                }
                call.respondBytes(bytes, type)
            }

            delete {
                val person = call.findPersonOrRespond(households(), people()) ?: return@delete
                val oldKey = person.photoKey ?: return@delete call.respond(person.toResponse())
                val updated = people().setPhoto(person.id, null)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, personNotFound)
                storage().delete(oldKey)
                call.respond(updated.toResponse())
            }
        }
    }
}
