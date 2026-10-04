package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PredictionServiceTest {
    private val household = Uuid.random()
    private val sumedh = person(household, "Sumedh")
    private val savar = person(household, "Savar")
    private val tj = person(household, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val owners = FakeItemOwners()
    private val predictions = FakePredictions()

    @AfterTest
    fun stop() = scope.cancel()

    private fun service(receipt: Receipt?): PredictionService {
        val people = FakePeople(listOf(sumedh, savar, tj))
        val sync = LabelSync(ml, people, scope)
        val receipts = FakeReceipts(listOfNotNull(receipt))
        val resync = MlResync(ml, receipts, people, sync)
        ml.historyResult = { MlResult.Success(MlHistoryStatus(it, 0, 30, false, null, historyDigest(emptyList()))) }
        return PredictionService(ml, resync, receipts, people, owners, predictions, now = { t0 })
    }

    private fun draft(vararg lines: (Uuid) -> com.monster0506.divvy.server.domain.ReceiptItem) =
        receipt(household, ReceiptStatus.DRAFT, confirmedAt = null) { id -> lines.map { it(id) } }

    private fun unassigned(name: String, line: Int = 1): (Uuid) -> com.monster0506.divvy.server.domain.ReceiptItem =
        { id -> item(id, name, emptyList(), null, line) }

    private fun prediction(itemId: Uuid, ownerIds: List<Uuid>, confidence: Double, alternatives: List<MlAlternative> = emptyList()) =
        MlItemPrediction(itemId.toString(), MlPredictionStatus.PREDICTED, ownerIds.map { it.toString() }, confidence, alternatives)

    private fun unknown(itemId: Uuid) = MlItemPrediction(itemId.toString(), MlPredictionStatus.UNKNOWN, emptyList(), 0.0)

    private fun answer(vararg items: MlItemPrediction, version: String = "combined-calibrated-120-ab12cd34") {
        ml.predictResult = { MlResult.Success(MlPredictResponse(version, items.toList())) }
    }

    private suspend fun run(receipt: Receipt): ReceiptPredictions =
        assertIs<PredictionResult.Done>(service(receipt).predictFor(receipt.id)).predictions

    // --- what is asked ----------------------------------------------------------------------

    @Test
    fun `the request carries the household, its active people and the items`() = runBlocking<Unit> {
        val r = draft(unassigned("Whole milk gallon"), unassigned("Paneer block", 2))
        answer()

        run(r)

        val request = ml.predictRequests.single()
        assertEquals(household.toString(), request.householdId)
        assertEquals(setOf("Sumedh", "Savar", "TJ"), request.people.map { it.name }.toSet())
        assertEquals(r.items.map { it.id.toString() }, request.items.map { it.itemId })
        assertEquals(listOf("Whole milk gallon", "Paneer block"), request.items.map { it.description })
    }

    @Test
    fun `the ML copy is checked before the model is asked`() = runBlocking<Unit> {
        answer()

        run(draft(unassigned("Eggs")))

        // an empty household in sync needs no rebuild, but the check happens first
        assertEquals(listOf("predict"), ml.events.toList())
        assertTrue(ml.historyCalls >= 1)
    }

    // --- what is stored ---------------------------------------------------------------------

    @Test
    fun `predicted owners are stored as model owners with their confidence`() = runBlocking<Unit> {
        val r = draft(unassigned("Coke Zero 12-pack"), unassigned("Paper towels", 2))
        answer(
            prediction(r.items[0].id, listOf(tj.id), 0.93),
            prediction(r.items[1].id, listOf(sumedh.id, savar.id, tj.id), 0.98),
        )

        val result = run(r)

        val (itemId, shares) = owners.calls.first()
        assertEquals(r.items[0].id, itemId)
        assertEquals(listOf(tj.id), shares.map { it.personId })
        assertTrue(shares.all { it.source == OwnershipSource.MODEL })
        assertEquals(BigDecimal("0.9300"), shares.single().confidence)
        assertEquals(3, owners.calls[1].second.size)
        assertEquals(PredictionState.PREDICTED, result.items[0].state)
        assertEquals(listOf(tj.id), result.items[0].ownerIds)
        assertEquals(0.93, result.items[0].confidence)
        assertEquals("combined-calibrated-120-ab12cd34", result.modelVersion)
        assertTrue(result.mlAvailable)
        assertNull(result.notice)
    }

    @Test
    fun `the model's record of each item is saved with its version and alternatives`() = runBlocking<Unit> {
        val r = draft(unassigned("Whole milk gallon"), unassigned("Mystery", 2))
        answer(
            prediction(r.items[0].id, listOf(tj.id), 0.93, listOf(MlAlternative(listOf(sumedh.id.toString(), tj.id.toString()), 0.16))),
            unknown(r.items[1].id),
            version = "v9",
        )

        run(r)

        val saved = predictions.saved.associateBy { it.receiptItemId }
        val milk = saved.getValue(r.items[0].id)
        assertEquals("v9", milk.modelVersion)
        assertEquals(MlPredictionStatus.PREDICTED, milk.status)
        assertEquals(0.93, milk.confidence)
        assertEquals(listOf(StoredAlternative(listOf(sumedh.id, tj.id), 0.16)), milk.alternatives)
        assertEquals(MlPredictionStatus.UNKNOWN, saved.getValue(r.items[1].id).status)
        assertEquals(household, milk.householdId)
    }

    @Test
    fun `an item the model does not know is left unassigned`() = runBlocking<Unit> {
        val r = draft(unassigned("zqxv wjkp"))
        answer(unknown(r.items[0].id))

        val result = run(r)

        assertTrue(owners.calls.isEmpty())
        assertEquals(PredictionState.UNKNOWN, result.items.single().state)
        assertNull(result.items.single().confidence)
        assertTrue(result.items.single().ownerIds.isEmpty())
    }

    @Test
    fun `an item the response forgot to mention is treated as unknown`() = runBlocking<Unit> {
        val r = draft(unassigned("Eggs"))
        answer()

        assertEquals(PredictionState.UNKNOWN, run(r).items.single().state)
    }

    @Test
    fun `a person's choice is never overwritten, though the prediction is still recorded`() = runBlocking<Unit> {
        val r = draft(
            { id -> item(id, "Eggs", listOf(sumedh), null, 1, OwnershipSource.USER) },
            unassigned("Rice", 2),
        )
        answer(prediction(r.items[0].id, listOf(tj.id), 0.9), prediction(r.items[1].id, listOf(tj.id), 0.9))

        val result = run(r)

        assertEquals(listOf(r.items[1].id), owners.calls.map { it.first })
        assertEquals(PredictionState.USER_ASSIGNED, result.items[0].state)
        assertEquals(listOf(sumedh.id), result.items[0].ownerIds)
        assertEquals(2, predictions.saved.size)
    }

    @Test
    fun `running again replaces an earlier model guess`() = runBlocking<Unit> {
        val r = draft({ id -> item(id, "Eggs", listOf(sumedh), null, 1, OwnershipSource.MODEL, BigDecimal("0.6000")) })
        answer(prediction(r.items[0].id, listOf(tj.id), 0.95))

        val result = run(r)

        assertEquals(listOf(tj.id), owners.calls.single().second.map { it.personId })
        assertEquals(PredictionState.PREDICTED, result.items.single().state)
    }

    @Test
    fun `a stale model guess is cleared when the model no longer knows the item`() = runBlocking<Unit> {
        val r = draft({ id -> item(id, "Eggs", listOf(sumedh), null, 1, OwnershipSource.MODEL, BigDecimal("0.6000")) })
        answer(unknown(r.items[0].id))

        val result = run(r)

        assertEquals(emptyList(), owners.calls.single().second)
        assertEquals(PredictionState.UNKNOWN, result.items.single().state)
    }

    @Test
    fun `owners who are not in the household are ignored`() = runBlocking<Unit> {
        val r = draft(unassigned("Eggs"))
        answer(prediction(r.items[0].id, listOf(tj.id, Uuid.random()), 0.9, listOf(MlAlternative(listOf(Uuid.random().toString()), 0.5))))

        val result = run(r)

        assertEquals(listOf(tj.id), owners.calls.single().second.map { it.personId })
        assertTrue(result.items.single().alternatives.isEmpty())
    }

    @Test
    fun `a prediction naming only strangers is unknown`() = runBlocking<Unit> {
        val r = draft(unassigned("Eggs"))
        answer(prediction(r.items[0].id, listOf(Uuid.random()), 0.9))

        assertEquals(PredictionState.UNKNOWN, run(r).items.single().state)
        assertTrue(owners.calls.isEmpty())
    }

    @Test
    fun `an out of range confidence is clamped`() = runBlocking<Unit> {
        val r = draft(unassigned("Eggs"))
        answer(prediction(r.items[0].id, listOf(tj.id), 1.7))

        run(r)

        assertEquals(BigDecimal("1.0000"), owners.calls.single().second.single().confidence)
    }

    // --- when it cannot predict -------------------------------------------------------------

    @Test
    fun `with the ML service down nothing changes and the person is told`() = runBlocking<Unit> {
        ml.predictResult = { MlResult.Unavailable("connection refused") }
        val r = draft(unassigned("Eggs"), { id -> item(id, "Rice", listOf(sumedh), null, 2) })

        val result = run(r)

        assertTrue(owners.calls.isEmpty())
        assertTrue(predictions.saved.isEmpty())
        assertEquals(false, result.mlAvailable)
        assertTrue(result.notice!!.contains("by hand"))
        assertEquals(PredictionState.UNAVAILABLE, result.items[0].state)
        assertEquals(PredictionState.USER_ASSIGNED, result.items[1].state)
        assertEquals(listOf(sumedh.id), result.items[1].ownerIds)
    }

    @Test
    fun `a refusal from the ML service is handled the same way`() = runBlocking<Unit> {
        ml.predictResult = { MlResult.Rejected(422, "bad request") }

        val result = run(draft(unassigned("Eggs")))

        assertEquals(false, result.mlAvailable)
        assertTrue(owners.calls.isEmpty())
    }

    @Test
    fun `a receipt that does not exist is reported as such`() = runBlocking<Unit> {
        assertEquals(PredictionResult.NotFound, service(null).predictFor(Uuid.random()))
    }

    @Test
    fun `a receipt that is already confirmed is not pre-assigned`() = runBlocking<Unit> {
        val confirmed = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        assertEquals(PredictionResult.NotDraft, service(confirmed).predictFor(confirmed.id))
        assertTrue(ml.predictRequests.isEmpty())
    }

    @Test
    fun `a receipt with no items does not call the ML service`() = runBlocking<Unit> {
        val empty = draft()

        val result = run(empty)

        assertTrue(ml.predictRequests.isEmpty())
        assertTrue(result.items.isEmpty())
    }

    // --- reading back -----------------------------------------------------------------------

    @Test
    fun `stored predictions can be read back without calling the ML service`() = runBlocking<Unit> {
        val r = draft(unassigned("Coke Zero"), unassigned("Rice", 2))
        val service = service(r)
        predictions.saved += StoredPrediction(r.items[0].id, household, "v3", MlPredictionStatus.PREDICTED, 0.9, emptyList(), t0)

        val result = assertIs<PredictionResult.Done>(service.stored(r.id)).predictions

        assertEquals(0.9, result.items[0].confidence)
        assertEquals(PredictionState.PREDICTED, result.items[0].state)
        assertEquals(PredictionState.UNAVAILABLE, result.items[1].state)
        assertEquals("v3", result.modelVersion)
        assertTrue(ml.predictRequests.isEmpty())
    }
}
