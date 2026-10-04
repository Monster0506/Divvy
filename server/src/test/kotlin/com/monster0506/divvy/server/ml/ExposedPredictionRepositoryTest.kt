package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.repository.RepositoryTestBase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ExposedPredictionRepositoryTest : RepositoryTestBase() {
    private val predictions = ExposedPredictionRepository(TestDatabase.db.database)

    private fun stored(itemId: Uuid, householdId: Uuid, version: String = "v1", confidence: Double = 0.9, alternatives: List<StoredAlternative> = emptyList(), status: MlPredictionStatus = MlPredictionStatus.PREDICTED) =
        StoredPrediction(itemId, householdId, version, status, confidence, alternatives, Instant.now())

    @Test
    fun `a prediction round-trips with its version, confidence and alternatives`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "TJ")
        val receipt = receipt(home, people[0], "Milk" to 429, "Eggs" to 549)
        val alternatives = listOf(StoredAlternative(listOf(people[0].id, people[1].id), 0.16), StoredAlternative(listOf(people[0].id), 0.05))

        predictions.save(listOf(stored(receipt.items[0].id, home.id, "combined-calibrated-120-ab12cd34", 0.9312, alternatives)))

        val read = predictions.forReceipt(receipt.id).single()
        assertEquals(receipt.items[0].id, read.receiptItemId)
        assertEquals(home.id, read.householdId)
        assertEquals("combined-calibrated-120-ab12cd34", read.modelVersion)
        assertEquals(MlPredictionStatus.PREDICTED, read.status)
        assertEquals(0.9312, read.confidence)
        assertEquals(alternatives, read.alternatives)
    }

    @Test
    fun `confidence is kept to four decimal places`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = receipt(home, people[0], "Milk" to 429)

        predictions.save(listOf(stored(receipt.items[0].id, home.id, confidence = 0.123456789)))

        assertEquals(0.1235, predictions.forReceipt(receipt.id).single().confidence)
    }

    @Test
    fun `an unknown answer is stored too`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = receipt(home, people[0], "Mystery" to 100)

        predictions.save(listOf(stored(receipt.items[0].id, home.id, confidence = 0.0, status = MlPredictionStatus.UNKNOWN)))

        assertEquals(MlPredictionStatus.UNKNOWN, predictions.forReceipt(receipt.id).single().status)
    }

    @Test
    fun `saving again replaces the earlier prediction for the item`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = receipt(home, people[0], "Milk" to 429)
        val itemId = receipt.items[0].id

        predictions.save(listOf(stored(itemId, home.id, "v1", 0.6)))
        predictions.save(listOf(stored(itemId, home.id, "v2", 0.95, listOf(StoredAlternative(listOf(people[0].id), 0.2)))))

        val read = predictions.forReceipt(receipt.id).single()
        assertEquals("v2", read.modelVersion)
        assertEquals(0.95, read.confidence)
        assertEquals(1, read.alternatives.size)
        assertEquals(1, predictions.countForHousehold(home.id))
    }

    @Test
    fun `only the receipt's own items come back`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val first = receipt(home, people[0], "Milk" to 429)
        val second = receipt(home, people[0], "Eggs" to 549)
        predictions.save(listOf(stored(first.items[0].id, home.id), stored(second.items[0].id, home.id)))

        assertEquals(listOf(first.items[0].id), predictions.forReceipt(first.id).map { it.receiptItemId })
        assertTrue(predictions.forReceipt(Uuid.random()).isEmpty())
    }

    @Test
    fun `counts are per household`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val (other, otherPeople) = household("Pat")
        val mine = receipt(home, people[0], "Milk" to 429, "Eggs" to 549)
        val theirs = receipt(other, otherPeople[0], "Rice" to 300)
        predictions.save(mine.items.map { stored(it.id, home.id) } + stored(theirs.items[0].id, other.id))

        assertEquals(2, predictions.countForHousehold(home.id))
        assertEquals(1, predictions.countForHousehold(other.id))
        assertEquals(0, predictions.countForHousehold(Uuid.random()))
    }

    @Test
    fun `saving nothing is fine`() = runBlocking<Unit> {
        predictions.save(emptyList())

        assertEquals(0, predictions.countForHousehold(Uuid.random()))
    }

    @Test
    fun `a prediction cannot point at another household's item`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val (other, _) = household("Pat")
        val receipt = receipt(home, people[0], "Milk" to 429)

        assertFailsWith<ExposedSQLException> {
            predictions.save(listOf(stored(receipt.items[0].id, other.id)))
        }
    }

    @Test
    fun `a confidence outside zero to one is rejected by the database`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = receipt(home, people[0], "Milk" to 429)

        assertFailsWith<ExposedSQLException> {
            predictions.save(listOf(stored(receipt.items[0].id, home.id, confidence = 1.5)))
        }
    }

    @Test
    fun `deleting a household takes its predictions with it`() = runBlocking<Unit> {
        val (home, people) = household("TJ")
        val receipt = receipt(home, people[0], "Milk" to 429)
        predictions.save(listOf(stored(receipt.items[0].id, home.id)))

        repos.households.delete(home.id)

        assertEquals(0, predictions.countForHousehold(home.id))
        assertEquals(0L, TestDatabase.query("SELECT count(*) AS n FROM item_prediction").single()["n"])
    }
}
