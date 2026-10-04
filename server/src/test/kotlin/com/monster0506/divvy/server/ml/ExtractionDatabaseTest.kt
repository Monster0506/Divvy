package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.ProcessedReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ExposedRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Upload to review on real tables: a processing receipt is read, filled in and pre-assigned. */
class ExtractionDatabaseTest {
    private val db = TestDatabase.db.database
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val storage = MemoryStorage()
    private val settings = ExposedMlSettingsRepository(db)
    private val sync = LabelSync(ml, com.monster0506.divvy.server.repository.ExposedPersonRepository(db), scope)
    private val repos = ExposedRepositories(db) { ConfirmationGuardReceiptRepository(SyncingReceiptRepository(it, sync), settings) }
    private val resync = MlResync(ml, repos.receipts, repos.people, sync)
    private val predictions = PredictionService(
        ml, resync, repos.receipts, repos.people, repos.itemOwners, ExposedPredictionRepository(db), settings,
    )
    private val extraction = ExtractionService(ml, repos.receipts, storage, predictions, scope)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    private suspend fun uploaded(householdName: String = "Home"): Triple<Uuid, Uuid, Uuid> {
        val home = repos.households.create(householdName, com.monster0506.divvy.server.domain.CurrencyCode("USD"))
        val tj = repos.people.add(home.id, "TJ")
        val receipt = repos.receipts.createProcessing(home.id, storage.put(byteArrayOf(1, 2, 3)))
        return Triple(home.id, tj.id, receipt.id)
    }

    private val read = MlExtractedReceipt(
        store = "Walmart",
        purchasedOn = "2026-09-18",
        items = listOf(
            MlExtractedItem("4CT BAN CHOC", "banana chocolate", 1.0, null, 398),
            MlExtractedItem("BANANAS", "bananas", 3.92, 48, 188),
        ),
        subtotalCents = 586,
        taxCents = 41,
        totalCents = 627,
    )

    @Test
    fun `a processing receipt becomes a draft with its items, totals and a MODEL guess`() = runBlocking<Unit> {
        val (_, tj, id) = uploaded()
        ml.extractResult = { MlResult.Success(read) }
        ml.predictResult = { request ->
            MlResult.Success(
                MlPredictResponse(
                    "v1",
                    request.items.map { MlItemPrediction(it.itemId, MlPredictionStatus.PREDICTED, listOf(tj.toString()), 0.93) },
                ),
            )
        }

        extraction.run(id)

        val receipt = assertNotNull(repos.receipts.findById(id))
        assertEquals(ReceiptStatus.DRAFT, receipt.status)
        assertEquals("Walmart", receipt.merchantName)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), receipt.purchasedAt)
        assertEquals(listOf(586L, 41L, 627L), listOf(receipt.subtotal!!.amountMinor, receipt.tax!!.amountMinor, receipt.total.amountMinor))
        assertEquals(listOf("banana chocolate", "bananas"), receipt.items.map { it.description })
        assertEquals(listOf("4CT BAN CHOC", "BANANAS"), receipt.items.map { it.rawText })
        assertEquals(listOf(1, 2), receipt.items.map { it.lineNumber })
        assertEquals(0, BigDecimal("3.92").compareTo(receipt.items[1].quantity))
        assertEquals(48L, receipt.items[1].unitPrice!!.amountMinor)
        val owner = receipt.items[0].owners.single()
        assertEquals(tj, owner.personId)
        assertEquals(OwnershipSource.MODEL, owner.source)
    }

    @Test
    fun `a failed read is stored with its reason and a retry reads it again`() = runBlocking<Unit> {
        val (_, _, id) = uploaded()
        ml.extractResult = { MlResult.Unavailable("down") }

        extraction.run(id)

        val failed = repos.receipts.findById(id)!!
        assertEquals(ReceiptStatus.FAILED, failed.status)
        assertNotNull(failed.failureReason)

        ml.extractResult = { MlResult.Success(read) }
        assertEquals(ReceiptStatus.PROCESSING, extraction.retry(id)!!.status)
        awaitStatus(id, ReceiptStatus.DRAFT)
        assertNull(repos.receipts.findById(id)!!.failureReason)
    }

    @Test
    fun `a receipt is filled in only once`() = runBlocking<Unit> {
        val (_, _, id) = uploaded()
        val processed = ProcessedReceipt("A", null, null, null, 100, listOf(NewReceiptItem("X", 100)))

        assertNotNull(repos.receipts.completeProcessing(id, processed))
        assertNull(repos.receipts.completeProcessing(id, processed))
        assertEquals(1, repos.receipts.findById(id)!!.items.size)
    }

    @Test
    fun `only processing receipts can fail and only failed ones can retry`() = runBlocking<Unit> {
        val (_, _, id) = uploaded()

        assertNull(repos.receipts.retryProcessing(id))
        assertNotNull(repos.receipts.failProcessing(id, "x".repeat(1000)))
        assertEquals(300, repos.receipts.findById(id)!!.failureReason!!.length)
        assertNull(repos.receipts.failProcessing(id, "again"))
        assertNotNull(repos.receipts.retryProcessing(id))
    }

    @Test
    fun `receipts still processing are found by status, oldest first`() = runBlocking<Unit> {
        val (home, _, first) = uploaded()
        val second = repos.receipts.createProcessing(home, storage.put(byteArrayOf(9)))
        val third = repos.receipts.createProcessing(home, storage.put(byteArrayOf(8)))
        repos.receipts.failProcessing(second.id, "x")

        assertEquals(listOf(first, third.id), repos.receipts.idsWithStatus(ReceiptStatus.PROCESSING))
        assertEquals(listOf(second.id), repos.receipts.idsWithStatus(ReceiptStatus.FAILED))
    }

    @Test
    fun `an extracted receipt can then be reviewed and confirmed like any draft`() = runBlocking<Unit> {
        val (_, tj, id) = uploaded()
        ml.extractResult = { MlResult.Success(read) }
        extraction.run(id)
        val items = repos.receipts.findById(id)!!.items
        items.forEach { repos.itemOwners.setOwners(it.id, listOf(com.monster0506.divvy.server.domain.OwnerShare(tj))) }

        val confirmed = repos.receipts.confirm(id)

        assertEquals(ReceiptStatus.CONFIRMED, confirmed!!.status)
    }

    @Test
    fun `the status check allows failed`() = runBlocking<Unit> {
        val (_, _, id) = uploaded()
        repos.receipts.failProcessing(id, "x")

        assertTrue(TestDatabase.query("SELECT status FROM receipt").single()["status"] == "failed")
    }

    private suspend fun awaitStatus(id: Uuid, status: ReceiptStatus) {
        repeat(200) {
            if (repos.receipts.findById(id)?.status == status) return
            delay(25)
        }
        error("receipt never reached $status")
    }
}
