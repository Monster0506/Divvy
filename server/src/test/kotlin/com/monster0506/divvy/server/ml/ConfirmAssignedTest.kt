package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.UnconfirmedItemsException
import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.ProcessedReceipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.ledger.balancesByCurrency
import com.monster0506.divvy.server.repository.ConfirmResult
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

/** The confirm the app uses (confirmAssigned) must do everything confirm does: balances, labels and the ML push. */
class ConfirmAssignedTest {
    private val db = TestDatabase.db.database
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val settings = ExposedMlSettingsRepository(db)
    private val sync = LabelSync(ml, ExposedPersonRepository(db), scope)
    private val repos = ExposedRepositories(db) { ConfirmationGuardReceiptRepository(SyncingReceiptRepository(it, sync), settings) }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    private suspend fun household() = repos.households.create("Home", CurrencyCode("USD")).let { home ->
        home to listOf("TJ", "Sam", "Ana").map { repos.people.add(home.id, it) }
    }

    @Test
    fun `confirming writes who owes whom`() = runBlocking<Unit> {
        val (home, people) = household()
        val (tj, sam, ana) = people
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, tj.id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 1200,
                listOf(
                    NewReceiptItem("Eggs", 600, owners = listOf(OwnerShare(sam.id))),
                    NewReceiptItem("Rice", 600, owners = listOf(OwnerShare(ana.id), OwnerShare(tj.id))),
                ),
            ),
        )

        val result = assertIs<ConfirmResult.Confirmed>(repos.receipts.confirmAssigned(receipt.id))

        assertEquals(ReceiptStatus.CONFIRMED, result.receipt.status)
        val balances = balancesByCurrency(repos.ledger.listByHousehold(home.id)).single()
        assertEquals(mapOf(tj.id to 900L, sam.id to -600L, ana.id to -300L), balances.netByPerson)
    }

    @Test
    fun `confirming records how each item's owners were decided`() = runBlocking<Unit> {
        val (home, people) = household()
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 100,
                listOf(NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(people[1].id)))),
            ),
        )

        repos.receipts.confirmAssigned(receipt.id)

        assertEquals(LabelSource.MANUAL, repos.receipts.findById(receipt.id)!!.items.single().labelSource)
    }

    @Test
    fun `confirming pushes the receipt's labels to the ML service`() = runBlocking<Unit> {
        val (home, people) = household()
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 300,
                listOf(
                    NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(people[1].id))),
                    NewReceiptItem("Rice", 200, owners = listOf(OwnerShare(people[2].id))),
                ),
            ),
        )

        repos.receipts.confirmAssigned(receipt.id)
        sync.awaitIdle()

        assertEquals(setOf("Eggs", "Rice"), ml.upserts.single().second.map { it.description }.toSet())
    }

    @Test
    fun `a guess a person accepted is labelled accepted and one they changed is labelled corrected`() = runBlocking<Unit> {
        val (home, people) = household()
        val (tj, sam, ana) = people
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, tj.id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 300,
                listOf(
                    NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(sam.id, 1, OwnershipSource.MODEL, BigDecimal("0.95")))),
                    NewReceiptItem("Rice", 200, owners = listOf(OwnerShare(sam.id, 1, OwnershipSource.MODEL, BigDecimal("0.95")))),
                ),
            ),
        )
        repos.itemOwners.setOwners(receipt.items[1].id, listOf(OwnerShare(ana.id)))

        repos.receipts.confirmAssigned(receipt.id)

        val sources = repos.receipts.findById(receipt.id)!!.items.associate { it.description to it.labelSource }
        assertEquals(LabelSource.PREDICTED_ACCEPTED, sources["Eggs"])
        assertEquals(LabelSource.PREDICTED_CORRECTED, sources["Rice"])
    }

    @Test
    fun `an unsure guess blocks the confirm the app uses, and nothing is written or pushed`() = runBlocking<Unit> {
        val (home, people) = household()
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 100,
                listOf(NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(people[1].id, 1, OwnershipSource.MODEL, BigDecimal("0.40"))))),
            ),
        )

        assertFailsWith<UnconfirmedItemsException> { repos.receipts.confirmAssigned(receipt.id) }
        sync.awaitIdle()

        assertEquals(ReceiptStatus.DRAFT, repos.receipts.findById(receipt.id)!!.status)
        assertTrue(repos.ledger.listByHousehold(home.id).isEmpty())
        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `a receipt nobody paid for is refused instead of confirmed with no balances`() = runBlocking<Unit> {
        val (home, people) = household()
        val processing = repos.receipts.createProcessing(home.id, "image")
        repos.receipts.completeProcessing(
            processing.id,
            ProcessedReceipt("Shop", null, null, null, 100, listOf(NewReceiptItem("Eggs", 100))),
        )
        repos.itemOwners.setOwners(repos.receipts.findById(processing.id)!!.items.single().id, listOf(OwnerShare(people[1].id)))

        assertEquals(ConfirmResult.NoPayer, repos.receipts.confirmAssigned(processing.id))
        assertEquals(ReceiptStatus.DRAFT, repos.receipts.findById(processing.id)!!.status)
    }

    @Test
    fun `reopening cancels the balances and confirming again writes them once`() = runBlocking<Unit> {
        val (home, people) = household()
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 500,
                listOf(NewReceiptItem("Eggs", 500, owners = listOf(OwnerShare(people[1].id)))),
            ),
        )
        repos.receipts.confirmAssigned(receipt.id)
        repos.receipts.reopen(receipt.id)
        assertTrue(balancesByCurrency(repos.ledger.listByHousehold(home.id)).single().netByPerson.values.all { it == 0L })

        repos.receipts.confirmAssigned(receipt.id)

        assertEquals(500L, balancesByCurrency(repos.ledger.listByHousehold(home.id)).single().netByPerson[people[0].id])
    }

    /** What the app's confirm used to do: change the status and nothing else. */
    private fun confirmTheOldWay(receiptId: kotlin.uuid.Uuid) {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
            exec("UPDATE receipt SET status = 'confirmed', confirmed_at = now() WHERE id = '$receiptId'")
        }
    }

    @Test
    fun `receipts confirmed the old way get their balances and labels on repair, once`() = runBlocking<Unit> {
        val (home, people) = household()
        val (tj, sam, _) = people
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, tj.id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 800,
                listOf(NewReceiptItem("Eggs", 800, owners = listOf(OwnerShare(sam.id)))),
            ),
        )
        confirmTheOldWay(receipt.id)
        assertTrue(repos.ledger.listByHousehold(home.id).isEmpty())
        assertEquals(null, repos.receipts.findById(receipt.id)!!.items.single().labelSource)

        assertEquals(1, repos.repairConfirmedReceipts())

        assertEquals(800L, balancesByCurrency(repos.ledger.listByHousehold(home.id)).single().netByPerson[tj.id])
        assertEquals(LabelSource.MANUAL, repos.receipts.findById(receipt.id)!!.items.single().labelSource)
        assertEquals(0, repos.repairConfirmedReceipts())
        assertEquals(1, repos.ledger.listByHousehold(home.id).size)
    }

    @Test
    fun `repair leaves receipts that are already right, drafts and other households alone`() = runBlocking<Unit> {
        val (home, people) = household()
        val fine = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 100,
                listOf(NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(people[1].id)))),
            ),
        )
        repos.receipts.confirmAssigned(fine.id)
        repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Draft", Instant.parse("2026-10-02T12:00:00Z"), CurrencyCode("USD"), null, null, 100,
                listOf(NewReceiptItem("Rice", 100, owners = listOf(OwnerShare(people[1].id)))),
            ),
        )
        val before = repos.ledger.listByHousehold(home.id).size

        assertEquals(0, repos.repairConfirmedReceipts())

        assertEquals(before, repos.ledger.listByHousehold(home.id).size)
    }

    @Test
    fun `a receipt whose owners are all the payer needs no balances and is not repaired again and again`() = runBlocking<Unit> {
        val (home, people) = household()
        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, people[0].id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 100,
                listOf(NewReceiptItem("Eggs", 100, owners = listOf(OwnerShare(people[0].id)))),
            ),
        )
        confirmTheOldWay(receipt.id)

        assertEquals(1, repos.repairConfirmedReceipts())
        assertEquals(0, repos.repairConfirmedReceipts())
    }
}
