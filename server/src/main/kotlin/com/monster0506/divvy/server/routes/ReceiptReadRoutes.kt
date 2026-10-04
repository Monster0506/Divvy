package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.repository.ReceiptRepository
import com.monster0506.divvy.server.storage.ImageStorage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class MoneyResponse(val amountMinor: Long, val currency: String)

@Serializable
data class ItemOwnerResponse(val personId: String, val shareWeight: Int, val source: String, val confidence: Double?)

@Serializable
data class ReceiptItemResponse(
    val id: String,
    val lineNumber: Int,
    val rawText: String?,
    val description: String,
    val quantity: String,
    val unitPrice: MoneyResponse?,
    val discount: MoneyResponse,
    val total: MoneyResponse,
    val owners: List<ItemOwnerResponse>,
    val labelSource: String?,
)

@Serializable
data class ReceiptDetailResponse(
    val id: String,
    val householdId: String,
    val paidByPersonId: String?,
    val merchantName: String?,
    val purchasedAt: String,
    val subtotal: MoneyResponse?,
    val tax: MoneyResponse?,
    val total: MoneyResponse,
    val status: String,
    val confirmedAt: String?,
    val hasImage: Boolean,
    val items: List<ReceiptItemResponse>,
    val createdAt: String,
    val updatedAt: String,
)

private fun Money.toResponse() = MoneyResponse(amountMinor, currency.code)

private fun ReceiptItem.toResponse() = ReceiptItemResponse(
    id = id.toString(),
    lineNumber = lineNumber,
    rawText = rawText,
    description = description,
    quantity = quantity.toPlainString(),
    unitPrice = unitPrice?.toResponse(),
    discount = discount.toResponse(),
    total = total.toResponse(),
    owners = owners.map {
        ItemOwnerResponse(it.personId.toString(), it.shareWeight, it.source.dbValue, it.confidence?.toDouble())
    },
    labelSource = labelSource?.dbValue,
)

internal fun Receipt.toDetailResponse() = ReceiptDetailResponse(
    id = id.toString(),
    householdId = householdId.toString(),
    paidByPersonId = paidByPersonId?.toString(),
    merchantName = merchantName,
    purchasedAt = purchasedAt.toString(),
    subtotal = subtotal?.toResponse(),
    tax = tax?.toResponse(),
    total = total.toResponse(),
    status = status.dbValue,
    confirmedAt = confirmedAt?.toString(),
    hasImage = imageKey != null,
    items = items.map { it.toResponse() },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private val receiptNotFound = ApiError("not_found", "receipt not found")
private val imageNotFound = ApiError("not_found", "receipt image not found")

/** Dependencies are resolved per request because the database connects after routing is set up. */
@OptIn(ExperimentalUuidApi::class)
fun Route.receiptReadRoutes(receipts: () -> ReceiptRepository, storage: () -> ImageStorage) {
    route("/receipts/{id}") {
        get {
            val receipt = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { receipts().findById(it) }
                ?: return@get call.respond(HttpStatusCode.NotFound, receiptNotFound)
            call.respond(receipt.toDetailResponse())
        }
        get("/image") {
            val receipt = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                ?.let { receipts().findById(it) }
                ?: return@get call.respond(HttpStatusCode.NotFound, receiptNotFound)
            val bytes = receipt.imageKey?.let { storage().get(it) }
                ?: return@get call.respond(HttpStatusCode.NotFound, imageNotFound)
            val type = when (detectImageType(bytes)) {
                ImageType.JPEG -> ContentType.Image.JPEG
                ImageType.PNG -> ContentType.Image.PNG
                ImageType.HEIC -> ContentType("image", "heic")
                null -> ContentType.Application.OctetStream
            }
            call.respondBytes(bytes, type)
        }
    }
}
