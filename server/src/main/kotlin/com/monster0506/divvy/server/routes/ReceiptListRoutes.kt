package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptCursor
import com.monster0506.divvy.server.domain.ReceiptFilter
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.HouseholdRepository
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val DEFAULT_RECEIPT_PAGE_SIZE = 50
const val MAX_RECEIPT_PAGE_SIZE = 100

@Serializable
data class ReceiptSummaryResponse(
    val id: String,
    val merchantName: String?,
    val purchasedAt: String,
    val paidByPersonId: String,
    val currency: String,
    val totalMinor: Long,
    val status: String,
    val itemCount: Int,
)

@Serializable
data class ReceiptPageResponse(val items: List<ReceiptSummaryResponse>, val nextCursor: String? = null)

private fun Receipt.toSummary() = ReceiptSummaryResponse(
    id = id.toString(),
    merchantName = merchantName,
    purchasedAt = purchasedAt.toString(),
    paidByPersonId = paidByPersonId.toString(),
    currency = currency.code,
    totalMinor = total.amountMinor,
    status = status.dbValue,
    itemCount = items.size,
)

internal fun encodeCursor(cursor: ReceiptCursor): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${cursor.purchasedAt}|${cursor.id}".toByteArray())

@OptIn(ExperimentalUuidApi::class)
internal fun decodeCursor(raw: String): ReceiptCursor? = runCatching {
    val (at, id) = String(Base64.getUrlDecoder().decode(raw)).split("|")
    ReceiptCursor(Instant.parse(at), Uuid.parse(id))
}.getOrNull()

/** Accepts a full ISO-8601 instant or a plain date, which means the start of that day in UTC. */
private fun parseWhen(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
    ?: runCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()

private fun invalid(message: String) = ApiError("validation_error", message)

@OptIn(ExperimentalUuidApi::class)
fun Route.receiptListRoutes(households: () -> HouseholdRepository, receipts: () -> ReceiptRepository) {
    get("/households/{id}/receipts") {
        val householdId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.takeIf { households().findById(it) != null }
            ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not_found", "household not found"))
        val q = call.request.queryParameters

        val status = q["status"]?.let { raw ->
            ReceiptStatus.entries.firstOrNull { it.dbValue == raw }
                ?: return@get call.respond(HttpStatusCode.BadRequest, invalid("status must be one of draft, confirmed, void"))
        }
        val from = q["from"]?.let {
            parseWhen(it) ?: return@get call.respond(HttpStatusCode.BadRequest, invalid("from must be an ISO-8601 date or time"))
        }
        val to = q["to"]?.let {
            parseWhen(it) ?: return@get call.respond(HttpStatusCode.BadRequest, invalid("to must be an ISO-8601 date or time"))
        }
        val payer = q["paidBy"]?.let { raw ->
            runCatching { Uuid.parse(raw) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, invalid("paidBy must be a person id"))
        }
        val cursor = q["cursor"]?.let {
            decodeCursor(it) ?: return@get call.respond(HttpStatusCode.BadRequest, invalid("cursor is not valid"))
        }
        val limit = q["limit"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_RECEIPT_PAGE_SIZE }
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    invalid("limit must be between 1 and $MAX_RECEIPT_PAGE_SIZE"),
                )
        } ?: DEFAULT_RECEIPT_PAGE_SIZE

        val filter = ReceiptFilter(status, q["merchant"]?.trim()?.takeIf { it.isNotEmpty() }, from, to, payer)
        val page = receipts().page(householdId, filter, cursor, limit)
        call.respond(ReceiptPageResponse(page.receipts.map { it.toSummary() }, page.next?.let(::encodeCursor)))
    }
}
