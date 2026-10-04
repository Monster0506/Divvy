package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.ProcessedReceipt
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ReceiptRepository
import com.monster0506.divvy.server.storage.ImageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Reads an uploaded receipt photo with the ML service and fills the receipt in, then asks for owner
 * predictions. It runs in the background after the upload returns, so the app polls the receipt: it is
 * `processing` until this finishes, then `draft` (or `failed` with a reason it can show and retry).
 */
class ExtractionService(
    private val ml: MlClient,
    private val receipts: ReceiptRepository,
    private val storage: ImageStorage,
    private val predictions: PredictionService,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ExtractionService::class.java)
    private val running = ConcurrentHashMap.newKeySet<Uuid>()

    /** Starts reading the receipt in the background. Asking twice while it runs does nothing. */
    fun schedule(receiptId: Uuid) {
        if (!running.add(receiptId)) return
        scope.launch {
            try {
                run(receiptId)
            } finally {
                running.remove(receiptId)
            }
        }
    }

    /** Picks up receipts a restart left in `processing`. */
    suspend fun resumePending() {
        receipts.idsWithStatus(ReceiptStatus.PROCESSING).forEach(::schedule)
    }

    /** Moves a failed receipt back to processing and reads it again. Null if it was not failed. */
    suspend fun retry(receiptId: Uuid): Receipt? =
        receipts.retryProcessing(receiptId)?.also { schedule(receiptId) }

    suspend fun run(receiptId: Uuid) {
        try {
            val receipt = receipts.findById(receiptId) ?: return
            if (receipt.status != ReceiptStatus.PROCESSING) return
            val key = receipt.imageKey ?: return fail(receiptId, "No image was stored for this receipt.")
            val bytes = storage.get(key) ?: return fail(receiptId, "The stored image could not be found.")

            when (val result = ml.extract(Base64.getEncoder().encodeToString(bytes))) {
                is MlResult.Success -> complete(receiptId, result.value)
                is MlResult.Unavailable -> {
                    log.warn("Extraction unavailable for receipt {}: {}", receiptId, result.reason)
                    fail(receiptId, "Reading receipts is not available right now. Try again in a moment.")
                }
                is MlResult.Rejected -> {
                    log.warn("Extraction rejected for receipt {}: {}", receiptId, result.message)
                    fail(receiptId, "The photo could not be read: ${result.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Extraction failed for receipt {}", receiptId, e)
            runCatching { fail(receiptId, "Something went wrong reading this receipt.") }
        }
    }

    private suspend fun complete(receiptId: Uuid, extracted: MlExtractedReceipt) {
        val processed = extracted.toProcessed()
        if (processed.items.isEmpty()) return fail(receiptId, "No items could be read from the photo.")

        if (receipts.completeProcessing(receiptId, processed) == null) return
        log.info(
            "Extracted receipt {}: {} items, {} warning(s) {}",
            receiptId, processed.items.size, extracted.warnings.size, extracted.warnings,
        )
        // Pre-assigning is best effort: a receipt with no guesses is still ready to review.
        runCatching { predictions.predictFor(receiptId) }
            .onFailure { if (it is CancellationException) throw it else log.warn("Prediction failed for {}", receiptId, it) }
    }

    private suspend fun fail(receiptId: Uuid, reason: String) {
        receipts.failProcessing(receiptId, reason)
    }
}

internal fun MlExtractedReceipt.toProcessed(): ProcessedReceipt {
    val lines = items.map { item ->
        NewReceiptItem(
            description = item.normalizedName?.takeIf { it.isNotBlank() } ?: item.name,
            totalMinor = item.totalCents,
            quantity = BigDecimal.valueOf(item.quantity.takeIf { it > 0 } ?: 1.0),
            unitPriceMinor = item.unitPriceCents,
            rawText = item.name,
        )
    } + discounts.map { NewReceiptItem(description = it.description, totalMinor = -it.amountCents, rawText = it.description) }

    return ProcessedReceipt(
        merchantName = store?.trim()?.takeIf { it.isNotEmpty() },
        purchasedAt = purchasedOn?.let(::parseDate),
        subtotalMinor = subtotalCents,
        taxMinor = taxCents,
        totalMinor = totalCents ?: lines.sumOf { it.totalMinor },
        items = lines,
    )
}

/** Noon UTC, so the calendar day survives a timezone shift when it is shown. */
private fun parseDate(raw: String): Instant? =
    try {
        LocalDate.parse(raw).atTime(NOON, 0).toInstant(ZoneOffset.UTC)
    } catch (e: DateTimeParseException) {
        null
    }

private const val NOON = 12
