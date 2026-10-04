package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptItemPatch
import com.monster0506.divvy.server.domain.ReceiptPatch
import com.monster0506.divvy.server.domain.reconcile
import com.monster0506.divvy.server.repository.PersonRepository
import com.monster0506.divvy.server.repository.ReceiptLockedException
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_MERCHANT_LENGTH = 120
private const val MAX_DESCRIPTION_LENGTH = 200
private const val MAX_QUANTITY = 1_000_000.0

@Serializable
data class UpdateReceiptRequest(
    val merchantName: String? = null,
    val purchasedAt: String? = null,
    val paidByPersonId: String? = null,
    val taxMinor: Long? = null,
    val totalMinor: Long? = null,
)

@Serializable
data class CreateReceiptItemRequest(
    val description: String? = null,
    val totalMinor: Long? = null,
    val quantity: Double? = null,
    val unitPriceMinor: Long? = null,
    val discountMinor: Long? = null,
)

@Serializable
data class UpdateReceiptItemRequest(
    val description: String? = null,
    val totalMinor: Long? = null,
    val quantity: Double? = null,
    val unitPriceMinor: Long? = null,
    val discountMinor: Long? = null,
)

@Serializable
data class MergeItemsRequest(val itemIds: List<String>? = null, val description: String? = null)

@Serializable
data class EditItemOwnerResponse(val personId: String, val shareWeight: Int)

@Serializable
data class EditReceiptItemResponse(
    val id: String,
    val lineNumber: Int,
    val rawText: String?,
    val description: String,
    val quantity: Double,
    val unitPriceMinor: Long?,
    val discountMinor: Long,
    val totalMinor: Long,
    val owners: List<EditItemOwnerResponse>,
)

@Serializable
data class ReconciliationResponse(
    val itemsTotalMinor: Long,
    val totalDifferenceMinor: Long,
    val subtotalDifferenceMinor: Long?,
    val reconciled: Boolean,
)

@Serializable
data class ReceiptResponse(
    val id: String,
    val householdId: String,
    val paidByPersonId: String?,
    val merchantName: String?,
    val purchasedAt: String,
    val currency: String,
    val subtotalMinor: Long?,
    val taxMinor: Long?,
    val totalMinor: Long,
    val status: String,
    val items: List<EditReceiptItemResponse>,
    val reconciliation: ReconciliationResponse,
)

private fun ReceiptItem.toResponse() = EditReceiptItemResponse(
    id = id.toString(),
    lineNumber = lineNumber,
    rawText = rawText,
    description = description,
    quantity = quantity.toDouble(),
    unitPriceMinor = unitPrice?.amountMinor,
    discountMinor = discount.amountMinor,
    totalMinor = total.amountMinor,
    owners = owners.map { EditItemOwnerResponse(it.personId.toString(), it.shareWeight) },
)

internal fun Receipt.toResponse(): ReceiptResponse {
    val reconciliation = reconcile()
    return ReceiptResponse(
        id = id.toString(),
        householdId = householdId.toString(),
        paidByPersonId = paidByPersonId?.toString(),
        merchantName = merchantName,
        purchasedAt = purchasedAt.toString(),
        currency = currency.code,
        subtotalMinor = subtotal?.amountMinor,
        taxMinor = tax?.amountMinor,
        totalMinor = total.amountMinor,
        status = status.dbValue,
        items = items.map { it.toResponse() },
        reconciliation = ReconciliationResponse(
            itemsTotalMinor = reconciliation.itemsTotalMinor,
            totalDifferenceMinor = reconciliation.totalDifferenceMinor,
            subtotalDifferenceMinor = reconciliation.subtotalDifferenceMinor,
            reconciled = reconciliation.reconciled,
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun parseUuid(raw: String?): Uuid? = raw?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private fun invalid(message: String) = ApiError("validation_error", message)
private val badJson = invalid("invalid JSON body")
private val badDescription = invalid("description must be between 1 and $MAX_DESCRIPTION_LENGTH characters")
private val badQuantity = invalid("quantity must be positive")
private val badDiscount = invalid("discountMinor must not be negative")
private val noFields = invalid("no fields to update")
private val receiptNotFound = ApiError("not_found", "receipt not found")
private val itemNotFound = ApiError("not_found", "receipt item not found")
private val locked = ApiError("conflict", "receipt is confirmed and can no longer be edited")

private fun cleanText(raw: String?, max: Int): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

/** Quantity rounded to the column's three decimals; null if it is not a usable positive number. */
private fun cleanQuantity(raw: Double): BigDecimal? {
    if (!raw.isFinite() || raw > MAX_QUANTITY) return null
    return BigDecimal.valueOf(raw).setScale(3, RoundingMode.HALF_UP).takeIf { it.signum() > 0 }
}

/** Receipt and line item edits. Repositories are resolved per request, like [householdRoutes]. */
@OptIn(ExperimentalUuidApi::class)
fun Route.receiptEditRoutes(receipts: () -> ReceiptRepository, people: () -> PersonRepository) {
    route("/receipts/{id}") {
        patch {
            val id = parseUuid(call.parameters["id"]) ?: return@patch call.respond(HttpStatusCode.NotFound, receiptNotFound)
            val body = runCatching { call.receive<UpdateReceiptRequest>() }.getOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
            val merchant = body.merchantName?.let {
                cleanText(it, MAX_MERCHANT_LENGTH)
                    ?: return@patch call.respond(
                        HttpStatusCode.BadRequest,
                        invalid("merchantName must be between 1 and $MAX_MERCHANT_LENGTH characters"),
                    )
            }
            val purchasedAt = body.purchasedAt?.let {
                runCatching { Instant.parse(it) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, invalid("purchasedAt must be an ISO-8601 instant"))
            }
            val payerId = body.paidByPersonId?.let {
                parseUuid(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, invalid("paidByPersonId must be a valid id"))
            }
            if ((body.taxMinor ?: 0) < 0 || (body.totalMinor ?: 0) < 0) {
                return@patch call.respond(HttpStatusCode.BadRequest, invalid("taxMinor and totalMinor must not be negative"))
            }
            val patch = ReceiptPatch(payerId, merchant, purchasedAt, body.taxMinor, body.totalMinor)
            if (patch == ReceiptPatch()) return@patch call.respond(HttpStatusCode.BadRequest, noFields)

            val current = receipts().findById(id) ?: return@patch call.respond(HttpStatusCode.NotFound, receiptNotFound)
            if (payerId != null && people().findById(payerId)?.householdId != current.householdId) {
                return@patch call.respond(
                    HttpStatusCode.BadRequest,
                    invalid("paidByPersonId must belong to the receipt's household"),
                )
            }
            respondEdit { receipts().update(id, patch) }
        }

        route("/items") {
            post {
                val id = parseUuid(call.parameters["id"]) ?: return@post call.respond(HttpStatusCode.NotFound, receiptNotFound)
                val body = runCatching { call.receive<CreateReceiptItemRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, badJson)
                val description = cleanText(body.description, MAX_DESCRIPTION_LENGTH)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, badDescription)
                val total = body.totalMinor
                    ?: return@post call.respond(HttpStatusCode.BadRequest, invalid("totalMinor is required"))
                val quantity = body.quantity?.let {
                    cleanQuantity(it) ?: return@post call.respond(HttpStatusCode.BadRequest, badQuantity)
                } ?: BigDecimal.ONE
                if ((body.discountMinor ?: 0) < 0) return@post call.respond(HttpStatusCode.BadRequest, badDiscount)

                val item = NewReceiptItem(
                    description = description,
                    totalMinor = total,
                    quantity = quantity,
                    unitPriceMinor = body.unitPriceMinor,
                    discountMinor = body.discountMinor ?: 0,
                )
                respondEdit(HttpStatusCode.Created) { receipts().addItem(id, item) }
            }

            post("/merge") {
                val id = parseUuid(call.parameters["id"]) ?: return@post call.respond(HttpStatusCode.NotFound, receiptNotFound)
                val body = runCatching { call.receive<MergeItemsRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, badJson)
                val itemIds = body.itemIds.orEmpty().map { parseUuid(it) }
                if (itemIds.size < 2 || itemIds.any { it == null } || itemIds.toSet().size != itemIds.size) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        invalid("itemIds must be two or more distinct item ids"),
                    )
                }
                val description = body.description?.let {
                    cleanText(it, MAX_DESCRIPTION_LENGTH) ?: return@post call.respond(HttpStatusCode.BadRequest, badDescription)
                }
                respondEdit(notFound = itemNotFound) { receipts().mergeItems(id, itemIds.filterNotNull(), description) }
            }

            route("/{itemId}") {
                patch {
                    val id = parseUuid(call.parameters["id"]) ?: return@patch call.respond(HttpStatusCode.NotFound, receiptNotFound)
                    val itemId = parseUuid(call.parameters["itemId"]) ?: return@patch call.respond(HttpStatusCode.NotFound, itemNotFound)
                    val body = runCatching { call.receive<UpdateReceiptItemRequest>() }.getOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, badJson)
                    val description = body.description?.let {
                        cleanText(it, MAX_DESCRIPTION_LENGTH) ?: return@patch call.respond(HttpStatusCode.BadRequest, badDescription)
                    }
                    val quantity = body.quantity?.let {
                        cleanQuantity(it) ?: return@patch call.respond(HttpStatusCode.BadRequest, badQuantity)
                    }
                    if ((body.discountMinor ?: 0) < 0) return@patch call.respond(HttpStatusCode.BadRequest, badDiscount)

                    val patch = ReceiptItemPatch(description, quantity, body.unitPriceMinor, body.discountMinor, body.totalMinor)
                    if (patch.isEmpty) return@patch call.respond(HttpStatusCode.BadRequest, noFields)
                    respondEdit(notFound = itemNotFound) { receipts().updateItem(id, itemId, patch) }
                }

                delete {
                    val id = parseUuid(call.parameters["id"]) ?: return@delete call.respond(HttpStatusCode.NotFound, receiptNotFound)
                    val itemId = parseUuid(call.parameters["itemId"]) ?: return@delete call.respond(HttpStatusCode.NotFound, itemNotFound)
                    respondEdit(notFound = itemNotFound) { receipts().deleteItem(id, itemId) }
                }
            }
        }
    }
}

/** Runs an edit and responds with the updated receipt, 404 if it returns null or 409 if the receipt is locked. */
private suspend fun RoutingContext.respondEdit(
    success: HttpStatusCode = HttpStatusCode.OK,
    notFound: ApiError = receiptNotFound,
    edit: suspend () -> Receipt?,
) {
    val updated = try {
        edit()
    } catch (_: ReceiptLockedException) {
        return call.respond(HttpStatusCode.Conflict, locked)
    }
    if (updated == null) call.respond(HttpStatusCode.NotFound, notFound) else call.respond(success, updated.toResponse())
}
