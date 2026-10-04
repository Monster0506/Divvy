package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.Person
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MlResyncTest {
    private val household = Uuid.random()
    private val sumedh = person(household, "Sumedh")
    private val tj = person(household, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private var clock = 1_000_000L

    @AfterTest
    fun stop() = scope.cancel()

    private val eggs = receipt(household) { id ->
        listOf(
            item(id, "Eggs", listOf(tj), LabelSource.MANUAL, 1),
            item(id, "Rice", listOf(sumedh), LabelSource.PREDICTED_ACCEPTED, 2),
        )
    }
    private val coke = receipt(household) { id -> listOf(item(id, "Coke Zero", listOf(tj), LabelSource.MANUAL)) }
    private val draft = receipt(household, ReceiptStatus.DRAFT, confirmedAt = null) { listOf(item(it, "Draft item", listOf(tj))) }
    private val voided = receipt(household, ReceiptStatus.VOID) { listOf(item(it, "Voided item", listOf(tj))) }

    private fun resync(receipts: List<com.monster0506.divvy.server.domain.Receipt> = listOf(eggs, coke, draft, voided)): Pair<MlResync, LabelSync> {
        val people = FakePeople(listOf(sumedh, tj))
        val sync = LabelSync(ml, people, scope)
        return MlResync(ml, FakeReceipts(receipts), people, sync, checkEveryMillis = 60_000, now = { clock }) to sync
    }

    private fun remote(digest: String?, size: Int, ready: Boolean = true) =
        MlResult.Success(MlHistoryStatus(household.toString(), size, 30, ready, "v1", digest))

    // --- the digest, pinned against the ML service ------------------------------------------

    private val fixture = listOf(
        MlObservation("obs-2", "2026-10-01", "Paneer block", listOf("p1", "p2", "p3"), listOf("p1", "p3")),
        MlObservation("obs-1", "2026-09-30", "Coke Zero 12-pack", listOf("p1", "p2"), listOf("p2")),
    )

    @Test
    fun `the digest equals the value the ML service computes for the same labels`() {
        // pinned in ml/tests/test_history_digest.py as FIXTURE_DIGEST
        assertEquals("876425c0cd0676cb37dabf243eda0efea760b19a67021a50b4c23957610b992f", historyDigest(fixture))
    }

    @Test
    fun `an empty history digests to the hash of nothing`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", historyDigest(emptyList()))
    }

    @Test
    fun `the digest ignores arrival order and the order of ids inside an observation`() {
        val shuffled = fixture.reversed().map { it.copy(ownerIds = it.ownerIds.reversed(), memberIds = it.memberIds.reversed()) }

        assertEquals(historyDigest(fixture), historyDigest(shuffled))
    }

    @Test
    fun `a changed owner, member, date or description changes the digest`() {
        val base = historyDigest(fixture)

        assertTrue(historyDigest(listOf(fixture[0].copy(ownerIds = listOf("p1")), fixture[1])) != base)
        assertTrue(historyDigest(listOf(fixture[0].copy(memberIds = listOf("p1", "p2", "p3", "p4")), fixture[1])) != base)
        assertTrue(historyDigest(listOf(fixture[0].copy(purchasedOn = "2026-10-02"), fixture[1])) != base)
        assertTrue(historyDigest(listOf(fixture[0].copy(description = "Paneer"), fixture[1])) != base)
    }

    @Test
    fun `how a label was made does not change the digest`() {
        val other = fixture.map { it.copy(source = MlAssignmentSource.PREDICTED_CORRECTED) }

        assertEquals(historyDigest(fixture), historyDigest(other))
    }

    // --- what the database holds ------------------------------------------------------------

    @Test
    fun `only labels from confirmed receipts count`() = runBlocking<Unit> {
        val (resync, _) = resync()

        val labels = resync.observationsFor(household)

        assertEquals(listOf("Eggs", "Rice", "Coke Zero"), labels.map { it.description })
    }

    @Test
    fun `another household's receipts are not included`() = runBlocking<Unit> {
        val other = Uuid.random()
        val foreign = receipt(other) { listOf(item(it, "Foreign", listOf(person(other, "Pat")))) }
        val (resync, _) = resync(listOf(eggs, foreign))

        assertEquals(listOf("Eggs", "Rice"), resync.observationsFor(household).map { it.description })
    }

    // --- status -----------------------------------------------------------------------------

    @Test
    fun `status says in sync when the digests match`() = runBlocking<Unit> {
        val (resync, _) = resync()
        val local = resync.observationsFor(household)
        ml.historyResult = { remote(historyDigest(local), 3) }

        val status = resync.status(household)

        assertEquals(MlSyncStatus(3, 3, true, false, true, "v1"), status)
    }

    @Test
    fun `status says out of sync when the content differs even if the count matches`() = runBlocking<Unit> {
        val (resync, _) = resync()
        ml.historyResult = { remote("0".repeat(64), 3) }

        val status = resync.status(household)

        assertEquals(false, status.inSync)
        assertEquals(3, status.mlLabels)
    }

    @Test
    fun `status falls back to the count when the ML service has no digest`() = runBlocking<Unit> {
        val (resync, _) = resync()

        ml.historyResult = { remote(null, 3) }
        assertEquals(true, resync.status(household).inSync)

        ml.historyResult = { remote(null, 2) }
        assertEquals(false, resync.status(household).inSync)
    }

    @Test
    fun `status reports the ML service as unreachable without failing`() = runBlocking<Unit> {
        val (resync, _) = resync()
        ml.historyResult = { MlResult.Unavailable("down") }

        val status = resync.status(household)

        assertEquals(3, status.databaseLabels)
        assertNull(status.mlLabels)
        assertNull(status.inSync)
    }

    @Test
    fun `status shows when a household is marked dirty`() = runBlocking<Unit> {
        val (resync, sync) = resync()
        ml.historyResult = { MlResult.Unavailable("down") }
        sync.markDirty(household)

        assertTrue(resync.status(household).dirty)
    }

    // --- repairing --------------------------------------------------------------------------

    @Test
    fun `an identical history is left alone`() = runBlocking<Unit> {
        val (resync, _) = resync()
        val local = resync.observationsFor(household)
        ml.historyResult = { remote(historyDigest(local), 3) }

        assertEquals(SyncCheck.IN_SYNC, resync.ensureSynced(household))

        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `a missed push is repaired by rebuilding the ML history from the database`() = runBlocking<Unit> {
        val (resync, sync) = resync()
        sync.markDirty(household)
        ml.historyResult = { remote(historyDigest(emptyList()), 0) }

        assertEquals(SyncCheck.REPAIRED, resync.ensureSynced(household))

        assertEquals(listOf(true), ml.replaces.toList())
        assertEquals(listOf("Eggs", "Rice", "Coke Zero"), ml.upserts.single().second.map { it.description })
        assertTrue(household !in sync.dirty)
    }

    @Test
    fun `a missed correction is repaired even though the counts agree`() = runBlocking<Unit> {
        val (resync, _) = resync()
        val stale = resync.observationsFor(household).map { if (it.description == "Eggs") it.copy(ownerIds = listOf(sumedh.id.toString())) else it }
        ml.historyResult = { remote(historyDigest(stale), 3) }

        assertEquals(SyncCheck.REPAIRED, resync.ensureSynced(household))

        val fixed = ml.upserts.single().second.first { it.description == "Eggs" }
        assertEquals(listOf(tj.id.toString()), fixed.ownerIds)
    }

    @Test
    fun `an ML copy that has labels the database no longer has is cleared`() = runBlocking<Unit> {
        val (resync, _) = resync(emptyList())
        ml.historyResult = { remote(historyDigest(fixture), 2) }

        assertEquals(SyncCheck.REPAIRED, resync.ensureSynced(household))

        assertTrue(ml.upserts.single().second.isEmpty())
        assertEquals(listOf(true), ml.replaces.toList())
    }

    @Test
    fun `an unreachable ML service fails the check and does not count as checked`() = runBlocking<Unit> {
        val (resync, _) = resync()
        ml.historyResult = { MlResult.Unavailable("down") }

        assertEquals(SyncCheck.FAILED, resync.ensureSynced(household))
        assertEquals(SyncCheck.FAILED, resync.ensureSynced(household))

        assertEquals(2, ml.historyCalls)
        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `a failed rebuild leaves the household marked for repair`() = runBlocking<Unit> {
        val (resync, sync) = resync()
        ml.historyResult = { remote("0".repeat(64), 1) }
        ml.upsertResult = { MlResult.Unavailable("down") }

        assertEquals(SyncCheck.FAILED, resync.ensureSynced(household))

        assertEquals(setOf(household), sync.dirty)
    }

    // --- not checking too often -------------------------------------------------------------

    @Test
    fun `a household checked a moment ago is not checked again`() = runBlocking<Unit> {
        val (resync, _) = resync()
        val local = resync.observationsFor(household)
        ml.historyResult = { remote(historyDigest(local), 3) }

        assertEquals(SyncCheck.IN_SYNC, resync.ensureSynced(household))
        clock += 30_000
        assertEquals(SyncCheck.SKIPPED, resync.ensureSynced(household))

        assertEquals(1, ml.historyCalls)
    }

    @Test
    fun `it checks again once the interval has passed`() = runBlocking<Unit> {
        val (resync, _) = resync()
        val local = resync.observationsFor(household)
        ml.historyResult = { remote(historyDigest(local), 3) }

        resync.ensureSynced(household)
        clock += 61_000

        assertEquals(SyncCheck.IN_SYNC, resync.ensureSynced(household))
        assertEquals(2, ml.historyCalls)
    }

    @Test
    fun `a dirty household is checked straight away`() = runBlocking<Unit> {
        val (resync, sync) = resync()
        val local = resync.observationsFor(household)
        ml.historyResult = { remote(historyDigest(local), 3) }
        resync.ensureSynced(household)

        sync.markDirty(household)

        assertEquals(SyncCheck.IN_SYNC, resync.ensureSynced(household))
        assertTrue(household !in sync.dirty)
    }

    // --- forcing ----------------------------------------------------------------------------

    @Test
    fun `a forced resync replaces the ML history even when it looked identical`() = runBlocking<Unit> {
        val (resync, _) = resync()
        ml.upsertResult = { MlResult.Success(MlUpsertResponse(household.toString(), 3, 3)) }

        val result = resync.resync(household)

        assertEquals(3, assertIs<MlResult.Success<MlUpsertResponse>>(result).value.historySize)
        assertEquals(listOf(true), ml.replaces.toList())
    }

    @Test
    fun `a forced resync with the ML service down says so and marks the household dirty`() = runBlocking<Unit> {
        val (resync, sync) = resync()
        ml.upsertResult = { MlResult.Unavailable("down") }

        assertIs<MlResult.Unavailable>(resync.resync(household))

        assertEquals(setOf(household), sync.dirty)
    }

    @Test
    fun `a resync waits for a push already running for the same household`() = runBlocking<Unit> {
        ml.upsertDelayMillis = 100
        val (resync, sync) = resync()

        sync.scheduleConfirmed(eggs)
        kotlinx.coroutines.delay(20)
        resync.resync(household)
        sync.awaitIdle()

        val h = household.toString()
        assertEquals(listOf("start $h", "end $h", "start $h", "end $h"), ml.events.toList())
        assertEquals(listOf(false, true), ml.replaces.toList())
    }

    @Test
    fun `archived people stay members of what was labelled while they lived there`() = runBlocking<Unit> {
        val left = person(household, "Left", archived = t0.plusSeconds(86_400))
        val people = FakePeople(listOf(sumedh, tj, left))
        val sync = LabelSync(ml, people, scope)
        val resync = MlResync(ml, FakeReceipts(listOf(eggs)), people, sync)

        val members = resync.observationsFor(household).first().memberIds

        assertTrue(left.id.toString() in members)
        assertFalse(person(household, "Nobody").id.toString() in members)
    }
}
