package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.Person
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The self-healing resync against a real database and a real ML service. Skipped unless
 * `ML_SERVICE_URL` is set. It also proves the Kotlin and Python digests agree on real data.
 */
class MlResyncLiveTest {
    private val url = System.getenv("ML_SERVICE_URL")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `the ML copy is detected as drifted and repaired from the database`(): Unit = runBlocking {
        assumeTrue("ML_SERVICE_URL is not set", !url.isNullOrBlank())
        val ml = KtorMlClient.create(url)
        val db = TestDatabase.db.database
        val sync = LabelSync(ml, ExposedPersonRepository(db), scope)
        val repos = ExposedRepositories(db) { SyncingReceiptRepository(it, sync) }
        val resync = MlResync(ml, repos.receipts, repos.people, sync, checkEveryMillis = 0)
        val home = repos.households.create("Resync live", CurrencyCode("USD"))
        val tj = repos.people.add(home.id, "TJ")
        val sumedh = repos.people.add(home.id, "Sumedh")
        val id = home.id.toString()

        fun line(name: String, owner: Person) =
            NewReceiptItem(name, 100, BigDecimal.ONE, 100, owners = listOf(OwnerShare(owner.id)))

        val receipt = repos.receipts.create(
            NewReceipt(
                home.id, tj.id, "Live", Instant.parse("2026-09-30T17:45:00Z"), CurrencyCode("USD"), null, null, 300,
                listOf(line("Coke Zero 12-pack", tj), line("Paneer block", sumedh), line("Eggs", tj)),
            ),
        )
        repos.receipts.confirm(receipt.id)
        sync.awaitIdle()

        val synced = resync.status(home.id)
        assertEquals(3, synced.databaseLabels)
        assertEquals(3, synced.mlLabels)
        assertEquals(true, synced.inSync, "the Kotlin and Python digests must agree on real data")

        // a delete the ML service never received
        assertEquals(MlResult.Success(Unit), ml.deleteObservation(id, receipt.items[2].id.toString()))
        assertEquals(false, resync.status(home.id).inSync)
        assertEquals(SyncCheck.REPAIRED, resync.ensureSynced(home.id))
        assertEquals(true, resync.status(home.id).inSync)
        assertEquals(3, resync.status(home.id).mlLabels)

        // a correction made in the database that the ML service never received: same count, different content
        repos.itemOwners.setOwners(receipt.items[0].id, listOf(OwnerShare(sumedh.id)))
        val drifted = resync.status(home.id)
        assertEquals(3, drifted.mlLabels)
        assertEquals(false, drifted.inSync)
        assertEquals(SyncCheck.REPAIRED, resync.ensureSynced(home.id))
        assertEquals(true, resync.status(home.id).inSync)

        // everything voided in the database, but the ML service still has the labels
        repos.receipts.void(receipt.id)
        sync.awaitIdle()
        assertTrue(assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(id)).value.historySize == 0)
        ml.upsertObservations(id, resync.observationsFor(home.id))
        val emptied = resync.resync(home.id)
        assertEquals(0, assertIs<MlResult.Success<MlUpsertResponse>>(emptied).value.historySize)
        assertEquals(true, resync.status(home.id).inSync)
        ml.close()
    }
}
