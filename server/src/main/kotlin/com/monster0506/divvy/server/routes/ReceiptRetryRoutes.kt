package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.ml.ExtractionService
import com.monster0506.divvy.server.repository.ReceiptRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.uuid.Uuid

/** Reads a receipt photo again after a failure. Answers 202: the work continues in the background. */
fun Route.receiptRetryRoutes(receipts: () -> ReceiptRepository, extraction: () -> ExtractionService) {
    post("/households/{id}/receipts/{receiptId}/retry") {
        val householdId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val receiptId = call.parameters["receiptId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val receipt = receiptId?.let { receipts().findById(it) }?.takeIf { it.householdId == householdId }
            ?: return@post call.respond(HttpStatusCode.NotFound, ApiError("not_found", "receipt not found"))

        val retried = extraction().retry(receipt.id)
            ?: return@post call.respond(
                HttpStatusCode.Conflict,
                ApiError("not_failed", "only a receipt whose reading failed can be retried"),
            )
        call.respond(HttpStatusCode.Accepted, ReceiptUploadResponse(retried.id.toString(), retried.status.dbValue))
    }
}
