package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.uuid.Uuid

/** Moves a confirmed receipt back to a draft so it can be corrected, cancelling the balances it created. */
fun Route.receiptReopenRoutes(receipts: () -> ReceiptRepository) {
    post("/receipts/{id}/reopen") {
        val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val receipt = id?.let { receipts().findById(it) }
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "receipt not found"))
        if (receipt.status != ReceiptStatus.CONFIRMED) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                ApiError("not_confirmed", "only a confirmed receipt can be reopened"),
            )
        }
        val reopened = receipts().reopen(receipt.id)
            ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("not_confirmed", "the receipt is no longer confirmed"))
        call.respond(reopened.toDetailResponse())
    }
}
