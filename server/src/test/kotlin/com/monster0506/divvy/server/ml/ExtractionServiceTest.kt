package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.storage.ImageStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MemoryStorage : ImageStorage {
    val files = ConcurrentHashMap<String, ByteArray>()

    override fun put(bytes: ByteArray): String = Uuid.random().toString().also { files[it] = bytes }

    override fun get(key: String): ByteArray? = files[key]

    override fun delete(key: String): Boolean = files.remove(key) != null
}

class ExtractionServiceTest {
    private val household = Uuid.random()
    private val tj = person(household, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val storage = MemoryStorage()
    private val photo = byteArrayOf(1, 2, 3, 4)

    @AfterTest
    fun stop() = scope.cancel()

    private fun processing(withImage: Boolean = true): Receipt {
        val key = if (withImage) storage.put(photo) else null
        return receipt(household, ReceiptStatus.PROCESSING, confirmedAt = null) { emptyList() }.copy(imageKey = key)
    }

    private fun service(receipts: FakeReceipts): ExtractionService {
        val people = FakePeople(listOf(tj))
        val resync = MlResync(ml, receipts, people, LabelSync(ml, people, scope))
        ml.historyResult = { MlResult.Success(MlHistoryStatus(it, 0, 30, false, null, historyDigest(emptyList()))) }
        val predictions = PredictionService(ml, resync, receipts, people, FakeItemOwners(), FakePredictions(), FakeSettings())
        return ExtractionService(ml, receipts, storage, predictions, scope)
    }

    private fun read(vararg items: MlExtractedItem, store: String? = "Walmart", date: String? = "2026-09-18", total: Long? = null) =
        MlExtractedReceipt(
            store = store,
            purchasedOn = date,
            items = items.toList(),
            subtotalCents = items.sumOf { it.totalCents },
            taxCents = 0,
            totalCents = total ?: items.sumOf { it.totalCents },
        )

    private val milk = MlExtractedItem("GV 2% MILK GAL", "great value 2% milk", 1.0, 398, 398)

    // --- a good read ------------------------------------------------------------------------------

    @Test
    fun `the photo is sent to the ML service as base64 and the receipt is filled in`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk)) }

        service(receipts).run(r.id)

        assertEquals(listOf(Base64.getEncoder().encodeToString(photo)), ml.extractRequests)
        val (_, processed) = receipts.completed.single()
        assertEquals("Walmart", processed.merchantName)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), processed.purchasedAt)
        assertEquals(398, processed.totalMinor)
        assertEquals(ReceiptStatus.DRAFT, receipts.findById(r.id)!!.status)
    }

    @Test
    fun `items keep the printed text and show the normalised name`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk, MlExtractedItem("MS ICE BIN", null, 2.0, 100, 200))) }

        service(receipts).run(r.id)

        val items = receipts.completed.single().second.items
        assertEquals(listOf("great value 2% milk", "MS ICE BIN"), items.map { it.description })
        assertEquals(listOf("GV 2% MILK GAL", "MS ICE BIN"), items.map { it.rawText })
        assertEquals(BigDecimal.valueOf(2.0), items[1].quantity)
        assertEquals(100L, items[1].unitPriceMinor)
    }

    @Test
    fun `an unrecognised date leaves the upload time and a missing total is summed from the items`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        val noTotal = read(milk, date = "sometime").copy(totalCents = null)
        ml.extractResult = { MlResult.Success(noTotal) }

        service(receipts).run(r.id)

        val processed = receipts.completed.single().second
        assertNull(processed.purchasedAt)
        assertEquals(398, processed.totalMinor)
    }

    @Test
    fun `a discount from the ML service becomes a negative line`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk).copy(discounts = listOf(MlDiscount("MEMBER SAVINGS", 100)))) }

        service(receipts).run(r.id)

        val items = receipts.completed.single().second.items
        assertEquals(listOf(398L, -100L), items.map { it.totalMinor })
        assertEquals("MEMBER SAVINGS", items[1].description)
    }

    @Test
    fun `predictions are requested once the receipt is filled in`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk)) }

        service(receipts).run(r.id)

        assertEquals(listOf("extract", "predict"), ml.events.filter { it == "extract" || it == "predict" })
    }

    // --- failures ---------------------------------------------------------------------------------

    @Test
    fun `an unavailable ML service fails the receipt with a message the app can show`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Unavailable("connection refused: 10.0.0.5") }

        service(receipts).run(r.id)

        val failed = receipts.findById(r.id)!!
        assertEquals(ReceiptStatus.FAILED, failed.status)
        assertTrue(failed.failureReason!!.contains("not available right now"))
        assertTrue(!failed.failureReason!!.contains("10.0.0.5"))
    }

    @Test
    fun `a refused photo fails the receipt with the ML message`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Rejected(422, "the image is not a JPEG, PNG or WebP") }

        service(receipts).run(r.id)

        assertEquals("The photo could not be read: the image is not a JPEG, PNG or WebP", receipts.findById(r.id)!!.failureReason)
    }

    @Test
    fun `a photo with no items fails the receipt instead of leaving an empty draft`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read()) }

        service(receipts).run(r.id)

        assertEquals(ReceiptStatus.FAILED, receipts.findById(r.id)!!.status)
        assertTrue(receipts.completed.isEmpty())
    }

    @Test
    fun `a missing image fails the receipt without calling the ML service`() = runBlocking<Unit> {
        val r = processing(withImage = false)
        val receipts = FakeReceipts(listOf(r))

        service(receipts).run(r.id)

        assertEquals(ReceiptStatus.FAILED, receipts.findById(r.id)!!.status)
        assertTrue(ml.extractRequests.isEmpty())
    }

    @Test
    fun `an exception from the ML client fails the receipt and does not escape`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { error("boom") }

        service(receipts).run(r.id)

        assertEquals(ReceiptStatus.FAILED, receipts.findById(r.id)!!.status)
    }

    @Test
    fun `a failed prediction does not undo a good read`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk)) }
        ml.predictResult = { error("predict exploded") }

        service(receipts).run(r.id)

        assertEquals(ReceiptStatus.DRAFT, receipts.findById(r.id)!!.status)
    }

    // --- scheduling -------------------------------------------------------------------------------

    @Test
    fun `a receipt that is not processing is left alone`() = runBlocking<Unit> {
        val r = processing().copy(status = ReceiptStatus.DRAFT)
        val receipts = FakeReceipts(listOf(r))

        service(receipts).run(r.id)

        assertTrue(ml.extractRequests.isEmpty())
    }

    @Test
    fun `retry puts a failed receipt back to processing and reads it again`() = runBlocking<Unit> {
        val r = processing().copy(status = ReceiptStatus.FAILED, failureReason = "x")
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { MlResult.Success(read(milk)) }
        val service = service(receipts)

        val retried = service.retry(r.id)

        assertEquals(ReceiptStatus.PROCESSING, retried!!.status)
        awaitStatus(receipts, r.id, ReceiptStatus.DRAFT)
        assertEquals(1, ml.extractRequests.size)
    }

    @Test
    fun `retry on a receipt that did not fail does nothing`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))

        assertNull(service(receipts).retry(r.id))
        assertTrue(ml.extractRequests.isEmpty())
    }

    @Test
    fun `resuming after a restart reads every receipt still processing`() = runBlocking<Unit> {
        val first = processing()
        val second = processing()
        val done = processing().copy(status = ReceiptStatus.DRAFT)
        val receipts = FakeReceipts(listOf(first, second, done))
        ml.extractResult = { MlResult.Success(read(milk)) }

        service(receipts).resumePending()

        awaitStatus(receipts, first.id, ReceiptStatus.DRAFT)
        awaitStatus(receipts, second.id, ReceiptStatus.DRAFT)
        assertEquals(2, ml.extractRequests.size)
    }

    @Test
    fun `scheduling the same receipt twice while it runs reads it once`() = runBlocking<Unit> {
        val r = processing()
        val receipts = FakeReceipts(listOf(r))
        ml.extractResult = { Thread.sleep(200); MlResult.Success(read(milk)) }
        val service = service(receipts)

        service.schedule(r.id)
        service.schedule(r.id)

        awaitStatus(receipts, r.id, ReceiptStatus.DRAFT)
        assertEquals(1, ml.extractRequests.size)
    }

    private suspend fun awaitStatus(receipts: FakeReceipts, id: Uuid, status: ReceiptStatus) {
        repeat(200) {
            if (receipts.findById(id)?.status == status) return
            kotlinx.coroutines.delay(25)
        }
        error("receipt never reached $status")
    }
}
