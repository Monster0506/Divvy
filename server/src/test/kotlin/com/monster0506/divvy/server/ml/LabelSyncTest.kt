package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.ReceiptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LabelSyncTest {
    private val household = Uuid.random()
    private val sumedh = person(household, "Sumedh")
    private val tj = person(household, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()

    @AfterTest
    fun stop() = scope.cancel()

    private fun sync(vararg people: com.monster0506.divvy.server.domain.Person) =
        LabelSync(ml, FakePeople(people.toList()), scope)

    // --- what gets sent ---------------------------------------------------------------------

    @Test
    fun `a confirmed receipt becomes one observation per owned item`() {
        val milk = receipt(household) { id ->
            listOf(
                item(id, "Whole milk gallon", listOf(tj), LabelSource.PREDICTED_ACCEPTED, 1),
                item(id, "Paneer block", listOf(sumedh), LabelSource.PREDICTED_CORRECTED, 2),
                item(id, "Paper towels", listOf(sumedh, tj), LabelSource.MANUAL, 3),
            )
        }

        val observations = observationsFor(milk, listOf(sumedh, tj))

        assertEquals(3, observations.size)
        assertEquals(milk.items.map { it.id.toString() }, observations.map { it.observationId })
        assertEquals(listOf("Whole milk gallon", "Paneer block", "Paper towels"), observations.map { it.description })
        assertEquals(
            listOf(MlAssignmentSource.PREDICTED_ACCEPTED, MlAssignmentSource.PREDICTED_CORRECTED, MlAssignmentSource.MANUAL),
            observations.map { it.source },
        )
        assertEquals(listOf(tj.id.toString()), observations[0].ownerIds)
        assertEquals(listOf(sumedh.id, tj.id).map { it.toString() }.sorted(), observations[2].ownerIds)
        assertEquals(listOf(sumedh.id, tj.id).map { it.toString() }.sorted(), observations[0].memberIds)
    }

    @Test
    fun `the purchase date is the receipt's date in UTC`() {
        val late = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        assertEquals("2026-09-30", observationsFor(late, listOf(tj)).single().purchasedOn)
    }

    @Test
    fun `items nobody owns are not labels`() {
        val r = receipt(household) { id ->
            listOf(item(id, "Mystery item", emptyList(), null, 1), item(id, "Eggs", listOf(tj), LabelSource.MANUAL, 2))
        }

        assertEquals(listOf("Eggs"), observationsFor(r, listOf(tj)).map { it.description })
    }

    @Test
    fun `someone who joined after the receipt was confirmed is not a member of it`() {
        val newcomer = person(household, "Newcomer", created = t0.plusSeconds(86_400))
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        val members = observationsFor(r, listOf(sumedh, tj, newcomer)).single().memberIds

        assertFalse(newcomer.id.toString() in members)
        assertTrue(sumedh.id.toString() in members)
    }

    @Test
    fun `someone archived after confirmation was still a member, someone archived before was not`() {
        val leftLater = person(household, "Left later", archived = t0.plusSeconds(86_400))
        val leftEarlier = person(household, "Left earlier", archived = t0.plusSeconds(60))
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        val members = observationsFor(r, listOf(tj, leftLater, leftEarlier)).single().memberIds

        assertTrue(leftLater.id.toString() in members)
        assertFalse(leftEarlier.id.toString() in members)
    }

    @Test
    fun `an owner is always a member even if the person records disagree`() {
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        assertEquals(listOf(tj.id.toString()), observationsFor(r, emptyList()).single().memberIds)
    }

    @Test
    fun `people from other households are never members`() {
        val stranger = person(Uuid.random(), "Stranger")
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }

        assertFalse(stranger.id.toString() in observationsFor(r, listOf(tj, stranger)).single().memberIds)
    }

    @Test
    fun `a receipt with no confirmation time has no labels`() {
        val draft = receipt(household, ReceiptStatus.DRAFT, confirmedAt = null) { listOf(item(it, "Eggs", listOf(tj))) }

        assertTrue(observationsFor(draft, listOf(tj)).isEmpty())
    }

    // --- pushing ----------------------------------------------------------------------------

    @Test
    fun `confirming pushes the labels once to the household's ML history`() = runBlocking<Unit> {
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj)), item(it, "Rice", listOf(sumedh), line = 2)) }
        val labelSync = sync(sumedh, tj)

        labelSync.scheduleConfirmed(r)
        labelSync.awaitIdle()

        val (sentTo, sent) = ml.upserts.single()
        assertEquals(household.toString(), sentTo)
        assertEquals(2, sent.size)
        assertTrue(labelSync.dirty.isEmpty())
    }

    @Test
    fun `a receipt that is not confirmed pushes nothing`() = runBlocking<Unit> {
        val draft = receipt(household, ReceiptStatus.DRAFT) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(draft)
        labelSync.awaitIdle()

        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `a confirmed receipt with no owned items sends nothing`() = runBlocking<Unit> {
        val r = receipt(household) { listOf(item(it, "Eggs", emptyList(), null)) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(r)
        labelSync.awaitIdle()

        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `an unavailable ML service marks the household dirty and nothing throws`() = runBlocking<Unit> {
        ml.upsertResult = { MlResult.Unavailable("down") }
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(r)
        labelSync.awaitIdle()

        assertEquals(setOf(household), labelSync.dirty)
    }

    @Test
    fun `a rejected push is logged but not retried, so the household is not dirty`() = runBlocking<Unit> {
        ml.upsertResult = { MlResult.Rejected(422, "bad request") }
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(r)
        labelSync.awaitIdle()

        assertTrue(labelSync.dirty.isEmpty())
    }

    @Test
    fun `an unexpected exception is contained and marks the household dirty`() = runBlocking<Unit> {
        ml.upsertResult = { throw IllegalStateException("boom") }
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(r)
        labelSync.awaitIdle()

        assertEquals(setOf(household), labelSync.dirty)
    }

    @Test
    fun `a household can be marked clean again`() {
        val labelSync = sync()
        labelSync.markDirty(household)
        labelSync.markClean(household)

        assertTrue(labelSync.dirty.isEmpty())
    }

    // --- voiding ----------------------------------------------------------------------------

    @Test
    fun `voiding removes each labelled item from the ML history`() = runBlocking<Unit> {
        val r = receipt(household, ReceiptStatus.CONFIRMED) { id ->
            listOf(item(id, "Eggs", listOf(tj), line = 1), item(id, "Rice", listOf(tj), line = 2))
        }
        val labelSync = sync(tj)

        labelSync.scheduleVoided(r)
        labelSync.awaitIdle()

        assertEquals(r.items.map { household.toString() to it.id.toString() }, ml.deletes.toList())
    }

    @Test
    fun `items that were never labelled are not deleted`() = runBlocking<Unit> {
        val r = receipt(household) { id ->
            listOf(item(id, "Eggs", listOf(tj), source = null, line = 1), item(id, "Rice", listOf(tj), line = 2))
        }
        val labelSync = sync(tj)

        labelSync.scheduleVoided(r)
        labelSync.awaitIdle()

        assertEquals(listOf(r.items[1].id.toString()), ml.deletes.map { it.second })
    }

    @Test
    fun `an item the ML service never had is fine to delete`() = runBlocking<Unit> {
        ml.deleteResult = { MlResult.Rejected(404, "observation not found") }
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleVoided(r)
        labelSync.awaitIdle()

        assertTrue(labelSync.dirty.isEmpty())
    }

    @Test
    fun `an unavailable ML service during a void marks the household dirty`() = runBlocking<Unit> {
        ml.deleteResult = { MlResult.Unavailable("down") }
        val r = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleVoided(r)
        labelSync.awaitIdle()

        assertEquals(setOf(household), labelSync.dirty)
    }

    // --- ordering ---------------------------------------------------------------------------

    @Test
    fun `changes to one household reach the ML service one at a time, in order`() = runBlocking<Unit> {
        ml.upsertDelayMillis = 80
        val first = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val second = receipt(household) { listOf(item(it, "Rice", listOf(tj))) }
        val labelSync = sync(tj)

        labelSync.scheduleConfirmed(first)
        labelSync.scheduleConfirmed(second)
        labelSync.awaitIdle()

        val h = household.toString()
        assertEquals(listOf("start $h", "end $h", "start $h", "end $h"), ml.events.toList())
        assertEquals(listOf("Eggs", "Rice"), ml.upserts.map { it.second.single().description })
    }

    @Test
    fun `different households do not wait for each other`() = runBlocking<Unit> {
        ml.upsertDelayMillis = 150
        val other = Uuid.random()
        val a = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        val b = receipt(other) { listOf(item(it, "Rice", listOf(person(other, "Pat")))) }
        val labelSync = sync(tj)

        val started = System.nanoTime()
        labelSync.scheduleConfirmed(a)
        labelSync.scheduleConfirmed(b)
        labelSync.awaitIdle()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(2, ml.upserts.size)
        assertTrue(elapsedMillis < 280, "took ${elapsedMillis}ms, so the two pushes ran back to back")
    }
}
