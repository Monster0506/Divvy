package com.monster0506.divvy.server.ml

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ConfirmResult
import com.monster0506.divvy.server.repository.ReceiptRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private class StubReceipts(
    var onConfirm: Receipt? = null,
    var onVoid: Receipt? = null,
) : ReceiptRepository {
    val calls = mutableListOf<String>()
    val found = Uuid.random()

    override suspend fun create(receipt: NewReceipt): Receipt = error("not used")

    override suspend fun findById(id: Uuid): Receipt? {
        calls += "findById"
        return null
    }

    override suspend fun listByHousehold(householdId: Uuid, limit: Int): List<Receipt> {
        calls += "list"
        return emptyList()
    }

    override suspend fun confirm(id: Uuid): Receipt? = onConfirm

    override suspend fun confirmAssigned(id: Uuid): ConfirmResult = error("not used")

    override suspend fun correctionRate(householdId: Uuid): Double? = 0.25

    override suspend fun void(id: Uuid): Receipt? = onVoid
}

class SyncingReceiptRepositoryTest {
    private val household = Uuid.random()
    private val tj = person(household, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val sync = LabelSync(ml, FakePeople(listOf(tj)), scope)
    private val stub = StubReceipts()
    private val repo = SyncingReceiptRepository(stub, sync)

    @AfterTest
    fun stop() = scope.cancel()

    @Test
    fun `confirming returns the receipt and pushes its labels`() = runBlocking<Unit> {
        val confirmed = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        stub.onConfirm = confirmed

        val result = repo.confirm(confirmed.id)
        sync.awaitIdle()

        assertSame(confirmed, result)
        assertEquals(1, ml.upserts.size)
    }

    @Test
    fun `confirming something that does not exist pushes nothing`() = runBlocking<Unit> {
        stub.onConfirm = null

        assertNull(repo.confirm(Uuid.random()))
        sync.awaitIdle()

        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `confirming a receipt that is still a draft pushes nothing`() = runBlocking<Unit> {
        stub.onConfirm = receipt(household, ReceiptStatus.DRAFT, confirmedAt = null) { listOf(item(it, "Eggs", listOf(tj))) }

        repo.confirm(Uuid.random())
        sync.awaitIdle()

        assertTrue(ml.upserts.isEmpty())
    }

    @Test
    fun `an unavailable ML service never changes what confirm returns`() = runBlocking<Unit> {
        ml.upsertResult = { MlResult.Unavailable("down") }
        val confirmed = receipt(household) { listOf(item(it, "Eggs", listOf(tj))) }
        stub.onConfirm = confirmed

        assertSame(confirmed, repo.confirm(confirmed.id))
        sync.awaitIdle()

        assertEquals(setOf(household), sync.dirty)
    }

    @Test
    fun `voiding a confirmed receipt removes its labels`() = runBlocking<Unit> {
        val voided = receipt(household, ReceiptStatus.VOID) { listOf(item(it, "Eggs", listOf(tj))) }
        stub.onVoid = voided

        assertSame(voided, repo.void(voided.id))
        sync.awaitIdle()

        assertEquals(listOf(voided.items.single().id.toString()), ml.deletes.map { it.second })
    }

    @Test
    fun `voiding a draft that was never labelled calls nothing`() = runBlocking<Unit> {
        stub.onVoid = receipt(household, ReceiptStatus.VOID) { listOf(item(it, "Eggs", listOf(tj), source = null)) }

        repo.void(Uuid.random())
        sync.awaitIdle()

        assertTrue(ml.deletes.isEmpty())
    }

    @Test
    fun `everything else passes straight through`() = runBlocking<Unit> {
        assertNull(repo.findById(Uuid.random()))
        assertEquals(emptyList(), repo.listByHousehold(household))
        assertEquals(0.25, repo.correctionRate(household))
        assertEquals(listOf("findById", "list"), stub.calls)
        assertTrue(ml.upserts.isEmpty() && ml.deletes.isEmpty())
    }

    @Test
    fun `the label source decides how an observation is tagged`() = runBlocking<Unit> {
        val confirmed = receipt(household) { id ->
            listOf(item(id, "Eggs", listOf(tj), LabelSource.PREDICTED_CORRECTED))
        }
        stub.onConfirm = confirmed

        repo.confirm(confirmed.id)
        sync.awaitIdle()

        assertEquals(MlAssignmentSource.PREDICTED_CORRECTED, ml.upserts.single().second.single().source)
    }
}
