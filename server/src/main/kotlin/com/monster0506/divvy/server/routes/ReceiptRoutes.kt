package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import com.monster0506.divvy.server.storage.ImageStorage
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class ReceiptUploadResponse(val id: String, val status: String)

enum class ImageType { JPEG, PNG, HEIC }

private val heifBrands = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

/** Identifies JPEG, PNG and HEIC images by their leading bytes, ignoring any declared content type. */
fun detectImageType(bytes: ByteArray): ImageType? {
    fun startsWith(vararg prefix: Int) =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> ImageType.JPEG
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageType.PNG
        bytes.size >= 12 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1).lowercase() in heifBrands -> ImageType.HEIC
        else -> null
    }
}

/** Dependencies are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.receiptRoutes(
    households: () -> HouseholdRepository,
    receipts: () -> ReceiptRepository,
    storage: () -> ImageStorage,
    maxUploadBytes: Long,
    onUploaded: (Uuid) -> Unit = {},
) {
    route("/households/{id}/receipts") {
        post {
            val householdId = runCatching { Uuid.parse(call.parameters["id"].orEmpty()) }.getOrNull()
                ?.takeIf { households().findById(it) != null }
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))

            var image: ByteArray? = null
            var tooLarge = false
            val multipart = runCatching { call.receiveMultipart() }.getOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("validation_error", "expected a multipart body with an image part"),
                )
            multipart.forEachPart { part ->
                if (part is PartData.FileItem && part.name == "image" && image == null && !tooLarge) {
                    // Read one byte past the limit so an oversize file is detected without buffering all of it.
                    val bytes = part.provider().readRemaining(maxUploadBytes + 1).readByteArray()
                    if (bytes.size > maxUploadBytes) tooLarge = true else image = bytes
                }
                part.dispose()
            }

            if (tooLarge) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("payload_too_large", "image must be at most $maxUploadBytes bytes"),
                )
            }
            val bytes = image
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("validation_error", "missing multipart file part named image"),
                )
            if (detectImageType(bytes) == null) {
                return@post call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ApiError("unsupported_media_type", "image must be JPEG, PNG or HEIC"),
                )
            }

            val key = storage().put(bytes)
            val receipt = try {
                receipts().createProcessing(householdId, key)
            } catch (e: Exception) {
                storage().delete(key)
                throw e
            }
            onUploaded(receipt.id)
            call.respond(
                HttpStatusCode.Created,
                ReceiptUploadResponse(receipt.id.toString(), receipt.status.dbValue),
            )
        }
    }
}
