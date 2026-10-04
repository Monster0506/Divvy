package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.UnconfirmedItemsException
import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ExposedPersonRepository
import com.monster0506.divvy.server.repository.ExposedRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The whole loop on real tables: predict, store, a person corrects one item, confirm, push, count. */
class PredictionDatabaseTest {
    private val db = TestDatabase.db.database
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val sync = LabelSync(ml, ExposedPersonRepository(db), scope)
    private val settings = ExposedMlSettingsRepository(db)
    private val repos = ExposedRepositories(db) { ConfirmationGuardReceiptRepository(SyncingReceiptRepository(it, sync), settings) }
    private val predictionRepository = ExposedPredictionRepository(db)
    private val resync = MlResync(ml, repos.receipts, repos.people, sync)
    private val service = PredictionService(ml, resync, repos.receipts, repos.people, repos.itemOwners, predictionRepository, settings)
    private val stats = MlStats(repos.receipts, predictionRepository)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    private suspend fun household(vararg names: String): Pair<Household, List<Person>> {
        val home = repos.households.create("Apartment 4B", CurrencyCode("USD"))
        return home to names.map { repos.people.add(home.id, it) }
    }

    private suspend fun draft(home: Household, payer: Person, vararg descriptions: String): Receipt =
        repos.receipts.create(
            NewReceipt(
                home.id, payer.id, "Fresh Market", Instant.parse("2026-09-30T17:45:00Z"), CurrencyCode("USD"),
                null, null, 100L * descriptions.size,
                descriptions.map { NewReceiptItem(it, 100, BigDecimal.ONE, 100) },
            ),
        )

    private fun answerWith(vararg items: Triple<Receipt, Int, Pair<List<Person>, Double>?>) {
        ml.predictResult = { request ->
            val byId = items.associate { (receipt, line, answer) -> receipt.items[line].id.toString() to answer }
            MlResult.Success(
                MlPredictResponse(
                    "combined-calibrated-40-abcd1234",
                    request.items.map { asked ->
                        val answer = byId[asked.itemId]
                        if (answer == null) {
                            MlItemPrediction(asked.itemId, MlPredictionStatus.UNKNOWN, emptyList(), 0.0)
                        } else {
                            MlItemPrediction(asked.itemId, MlPredictionStatus.PREDICTED, answer.first.map { it.id.toString() }, answer.second)
                        }
                    },
                ),
            )
        }
    }

    @Test
    fun `predict then correct one item then confirm and owners, label sources, push and stats all agree`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar", "TJ")
        val (sumedh, savar, tj) = people
        val receipt = draft(home, tj, "Coke Zero 12-pack", "Paneer block", "Mystery thing")
        answerWith(
            Triple(receipt, 0, listOf(tj) to 0.95),
            Triple(receipt, 1, listOf(sumedh) to 0.90),
        )

        val done = assertIs<PredictionResult.Done>(service.predictFor(receipt.id)).predictions

        assertEquals(listOf(PredictionState.PREDICTED, PredictionState.PREDICTED, PredictionState.UNKNOWN), done.items.map { it.state })
        val cokeOwner = repos.itemOwners.ownersOf(receipt.items[0].id).single()
        assertEquals(tj.id, cokeOwner.personId)
        assertEquals(OwnershipSource.MODEL, cokeOwner.source)
        assertEquals(0, BigDecimal("0.9500").compareTo(cokeOwner.confidence))
        assertTrue(repos.itemOwners.ownersOf(receipt.items[2].id).isEmpty())
        assertEquals(3, predictionRepository.forReceipt(receipt.id).size)
        assertEquals("combined-calibrated-40-abcd1234", predictionRepository.forReceipt(receipt.id).first().modelVersion)

        // a person disagrees about the paneer and fills in the unknown item
        repos.itemOwners.setOwners(receipt.items[1].id, listOf(OwnerShare(savar.id)))
        repos.itemOwners.setOwners(receipt.items[2].id, listOf(OwnerShare(sumedh.id), OwnerShare(tj.id)))
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        val sources = repos.receipts.findById(receipt.id)!!.items.associate { it.description to it.labelSource }
        assertEquals(LabelSource.PREDICTED_ACCEPTED, sources["Coke Zero 12-pack"])
        assertEquals(LabelSource.PREDICTED_CORRECTED, sources["Paneer block"])
        assertEquals(LabelSource.MANUAL, sources["Mystery thing"])

        val pushed = ml.upserts.single().second.associateBy { it.description }
        assertEquals(MlAssignmentSource.PREDICTED_ACCEPTED, pushed.getValue("Coke Zero 12-pack").source)
        assertEquals(MlAssignmentSource.PREDICTED_CORRECTED, pushed.getValue("Paneer block").source)
        assertEquals(listOf(savar.id.toString()), pushed.getValue("Paneer block").ownerIds)
        assertEquals(MlAssignmentSource.MANUAL, pushed.getValue("Mystery thing").source)

        val summary = stats.forHousehold(home.id)
        assertEquals(MlStatsSummary(3, 1, 1, 1, 0.5, 3), summary)
    }

    @Test
    fun `an item a person already assigned is left alone on the next prediction`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], "Eggs", "Rice")
        repos.itemOwners.setOwners(receipt.items[0].id, listOf(OwnerShare(people[0].id)))
        answerWith(Triple(receipt, 0, listOf(people[1]) to 0.9), Triple(receipt, 1, listOf(people[1]) to 0.9))

        val done = assertIs<PredictionResult.Done>(service.predictFor(receipt.id)).predictions

        assertEquals(PredictionState.USER_ASSIGNED, done.items[0].state)
        val eggs = repos.itemOwners.ownersOf(receipt.items[0].id).single()
        assertEquals(people[0].id, eggs.personId)
        assertEquals(OwnershipSource.USER, eggs.source)
        assertEquals(OwnershipSource.MODEL, repos.itemOwners.ownersOf(receipt.items[1].id).single().source)
    }

    @Test
    fun `predicting again replaces the earlier guess and does not stack owners`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], "Eggs")

        answerWith(Triple(receipt, 0, listOf(people[0]) to 0.6))
        service.predictFor(receipt.id)
        answerWith(Triple(receipt, 0, listOf(people[1]) to 0.97))
        service.predictFor(receipt.id)

        val owners = repos.itemOwners.ownersOf(receipt.items[0].id)
        assertEquals(listOf(people[1].id), owners.map { it.personId })
        assertEquals(1, predictionRepository.countForHousehold(home.id))
        assertEquals(0.97, predictionRepository.forReceipt(receipt.id).single().confidence)
    }

    @Test
    fun `with the ML service down the database is untouched and the person is told`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], "Eggs")
        ml.predictResult = { MlResult.Unavailable("connection refused") }

        val done = assertIs<PredictionResult.Done>(service.predictFor(receipt.id)).predictions

        assertEquals(false, done.mlAvailable)
        assertTrue(done.notice!!.isNotBlank())
        assertTrue(repos.itemOwners.ownersOf(receipt.items[0].id).isEmpty())
        assertEquals(0, predictionRepository.countForHousehold(home.id))
    }

    @Test
    fun `a confirmed receipt cannot be pre-assigned`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], "Eggs")
        repos.itemOwners.setOwners(receipt.items[0].id, listOf(OwnerShare(people[0].id)))
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        assertEquals(PredictionResult.NotDraft, service.predictFor(receipt.id))
    }

    @Test
    fun `a person who left is not offered as an owner`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], "Eggs")
        repos.people.archive(people[0].id)
        answerWith(Triple(receipt, 0, listOf(people[0], people[1]) to 0.9))

        service.predictFor(receipt.id)

        assertEquals(listOf(people[1].id), repos.itemOwners.ownersOf(receipt.items[0].id).map { it.personId })
        assertEquals(listOf(people[1].id.toString()), ml.predictRequests.single().people.map { it.id })
    }

    @Test
    fun `a receipt with an unsure guess cannot be confirmed until the guess is accepted`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], "Coke Zero 12-pack", "Paneer block")
        answerWith(Triple(receipt, 0, listOf(people[1]) to 0.95), Triple(receipt, 1, listOf(people[0]) to 0.60))
        service.predictFor(receipt.id)

        val refused = assertFailsWith<UnconfirmedItemsException> { repos.receipts.confirm(receipt.id) }
        sync.awaitIdle()

        assertEquals(listOf(receipt.items[1].id), refused.itemIds)
        assertEquals(ReceiptStatus.DRAFT, repos.receipts.findById(receipt.id)!!.status)
        assertTrue(ml.upserts.isEmpty())

        val accepted = service.accept(receipt.id, null)
        assertEquals(AcceptResult.Done(listOf(receipt.items[1].id)), accepted)
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        val sources = repos.receipts.findById(receipt.id)!!.items.associate { it.description to it.labelSource }
        assertEquals(LabelSource.PREDICTED_ACCEPTED, sources["Paneer block"])
        assertEquals(LabelSource.PREDICTED_ACCEPTED, sources["Coke Zero 12-pack"])
        assertEquals(1, ml.upserts.size)
    }

    @Test
    fun `lowering the threshold lets the same guesses through`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], "Eggs")
        answerWith(Triple(receipt, 0, listOf(people[0]) to 0.60))
        service.predictFor(receipt.id)
        assertFailsWith<UnconfirmedItemsException> { repos.receipts.confirm(receipt.id) }

        settings.save(home.id, MlSettings(true, 0.5))

        assertEquals(ReceiptStatus.CONFIRMED, repos.receipts.confirm(receipt.id)!!.status)
    }

    @Test
    fun `with auto-assign off every guess has to be accepted`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], "Eggs")
        settings.save(home.id, MlSettings(false, 0.85))
        answerWith(Triple(receipt, 0, listOf(people[0]) to 0.99))

        val view = assertIs<PredictionResult.Done>(service.predictFor(receipt.id)).predictions.items.single()

        assertTrue(view.needsConfirmation)
        assertFailsWith<UnconfirmedItemsException> { repos.receipts.confirm(receipt.id) }
    }

    @Test
    fun `accepting only some items leaves the rest pending`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], "Eggs", "Rice")
        answerWith(Triple(receipt, 0, listOf(people[0]) to 0.5), Triple(receipt, 1, listOf(people[0]) to 0.5))
        service.predictFor(receipt.id)

        service.accept(receipt.id, setOf(receipt.items[0].id))

        val refused = assertFailsWith<UnconfirmedItemsException> { repos.receipts.confirm(receipt.id) }
        assertEquals(listOf(receipt.items[1].id), refused.itemIds)
    }
}
