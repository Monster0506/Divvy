package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.repository.ExposedPersonRepository
import com.monster0506.divvy.server.repository.ExposedRepositories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real repositories on a real database, with the sync decorator in place and an in-memory ML fake. */
class LabelSyncDatabaseTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeMl()
    private val sync = LabelSync(fake, ExposedPersonRepository(TestDatabase.db.database), scope)
    private val repos = ExposedRepositories(TestDatabase.db.database) { SyncingReceiptRepository(it, sync) }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    private suspend fun household(vararg names: String): Pair<Household, List<Person>> {
        val home = repos.households.create("Apartment 4B", CurrencyCode("USD"))
        return home to names.map { repos.people.add(home.id, it) }
    }

    private suspend fun draft(home: Household, payer: Person, vararg items: NewReceiptItem): Receipt =
        repos.receipts.create(
            NewReceipt(
                householdId = home.id,
                paidByPersonId = payer.id,
                merchantName = "Fresh Market",
                purchasedAt = Instant.parse("2026-09-30T17:45:00Z"),
                currency = CurrencyCode("USD"),
                subtotalMinor = items.sumOf { it.totalMinor },
                taxMinor = 0,
                totalMinor = items.sumOf { it.totalMinor },
                items = items.toList(),
            ),
        )

    private fun line(description: String, vararg owners: OwnerShare) =
        NewReceiptItem(description, 500, BigDecimal.ONE, 500, owners = owners.toList())

    private fun predicted(person: Person) = OwnerShare(person.id, 1, OwnershipSource.MODEL, BigDecimal("0.9"))

    @Test
    fun `confirming a receipt sends each item with its owners, members and how it was labelled`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar", "TJ")
        val (sumedh, savar, tj) = people
        val receipt = draft(
            home, tj,
            line("Coke Zero 12-pack", predicted(tj)),
            line("Paneer block", predicted(sumedh)),
            line("Paper towels", OwnerShare(sumedh.id), OwnerShare(savar.id), OwnerShare(tj.id)),
        )
        // the person disagreed with the model about the paneer
        repos.itemOwners.setOwners(receipt.items[1].id, listOf(OwnerShare(savar.id)))

        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        val sent = fake.upserts.single().second.associateBy { it.description }
        assertEquals(home.id.toString(), fake.upserts.single().first)
        assertEquals(3, sent.size)
        assertEquals(MlAssignmentSource.PREDICTED_ACCEPTED, sent.getValue("Coke Zero 12-pack").source)
        assertEquals(listOf(tj.id.toString()), sent.getValue("Coke Zero 12-pack").ownerIds)
        assertEquals(MlAssignmentSource.PREDICTED_CORRECTED, sent.getValue("Paneer block").source)
        assertEquals(listOf(savar.id.toString()), sent.getValue("Paneer block").ownerIds)
        assertEquals(MlAssignmentSource.MANUAL, sent.getValue("Paper towels").source)
        assertEquals(people.map { it.id.toString() }.sorted(), sent.getValue("Paper towels").ownerIds)
        assertEquals(people.map { it.id.toString() }.sorted(), sent.getValue("Coke Zero 12-pack").memberIds)
        assertEquals(receipt.items.map { it.id.toString() }.toSet(), sent.values.map { it.observationId }.toSet())
        assertEquals("2026-09-30", sent.getValue("Paper towels").purchasedOn)
        assertTrue(sync.dirty.isEmpty())
    }

    @Test
    fun `a person who joins after confirmation is not a member of the old labels`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], line("Eggs", OwnerShare(people[1].id)))
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()
        val newcomer = repos.people.add(home.id, "Newcomer")

        val second = draft(home, people[1], line("Rice", OwnerShare(newcomer.id)))
        repos.receipts.confirm(second.id)
        sync.awaitIdle()

        val first = fake.upserts[0].second.single()
        val later = fake.upserts[1].second.single()
        assertFalse(newcomer.id.toString() in first.memberIds)
        assertTrue(newcomer.id.toString() in later.memberIds)
    }

    @Test
    fun `an archived person stays a member of the labels made while they lived there`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = draft(home, people[1], line("Eggs", OwnerShare(people[1].id)))
        repos.receipts.confirm(receipt.id)
        repos.people.archive(people[0].id)
        sync.awaitIdle()

        assertTrue(people[0].id.toString() in fake.upserts.single().second.single().memberIds)
    }

    @Test
    fun `voiding a confirmed receipt deletes its labels`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], line("Eggs", OwnerShare(people[0].id)), line("Rice", OwnerShare(people[0].id)))
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        repos.receipts.void(receipt.id)
        sync.awaitIdle()

        assertEquals(receipt.items.map { it.id.toString() }.toSet(), fake.deletes.map { it.second }.toSet())
        assertEquals(home.id.toString(), fake.deletes.first().first)
    }

    @Test
    fun `an ML outage leaves confirm working and the household marked for repair`() = runBlocking<Unit> {
        fake.upsertResult = { MlResult.Unavailable("down") }
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], line("Eggs", OwnerShare(people[0].id)))

        val confirmed = repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        assertEquals(com.monster0506.divvy.server.domain.ReceiptStatus.CONFIRMED, confirmed?.status)
        assertEquals(setOf(home.id), sync.dirty)
    }

    @Test
    fun `confirming twice sends the same observation ids both times`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = draft(home, people[0], line("Eggs", OwnerShare(people[0].id)))

        repos.receipts.confirm(receipt.id)
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        val ids = fake.upserts.map { upsert -> upsert.second.map { it.observationId } }
        assertEquals(1, ids.distinct().size)
    }

    @Test
    fun `the wrapped repository is still the same interface`() {
        assertIs<SyncingReceiptRepository>(repos.receipts)
    }
}

/** The same flow against a real ML service. Skipped unless `ML_SERVICE_URL` is set. */
class LabelSyncLiveTest {
    private val url = System.getenv("ML_SERVICE_URL")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `confirming and voiding in the database changes the ML service's history`(): Unit = runBlocking {
        assumeTrue("ML_SERVICE_URL is not set", !url.isNullOrBlank())
        val ml = KtorMlClient.create(url)
        val sync = LabelSync(ml, ExposedPersonRepository(TestDatabase.db.database), scope)
        val repos = ExposedRepositories(TestDatabase.db.database) { SyncingReceiptRepository(it, sync) }
        val home = repos.households.create("Live test", CurrencyCode("USD"))
        val tj = repos.people.add(home.id, "TJ")
        val sumedh = repos.people.add(home.id, "Sumedh")

        fun receiptOf(vararg items: NewReceiptItem) = NewReceipt(
            home.id, tj.id, "Live", Instant.parse("2026-09-30T17:45:00Z"), CurrencyCode("USD"), null, null,
            items.sumOf { it.totalMinor }, items.toList(),
        )
        fun item(name: String, owner: Person) = NewReceiptItem(name, 100, BigDecimal.ONE, 100, owners = listOf(OwnerShare(owner.id)))

        val first = repos.receipts.create(receiptOf(item("Coke Zero 12-pack", tj), item("Paneer block", sumedh)))
        repos.receipts.confirm(first.id)
        sync.awaitIdle()
        assertEquals(2, assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(home.id.toString())).value.historySize)

        val second = repos.receipts.create(receiptOf(item("Eggs", tj)))
        repos.receipts.confirm(second.id)
        sync.awaitIdle()
        assertEquals(3, assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(home.id.toString())).value.historySize)

        repos.receipts.void(first.id)
        sync.awaitIdle()
        assertEquals(1, assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(home.id.toString())).value.historySize)
        assertTrue(sync.dirty.isEmpty())
        ml.close()
    }
}
