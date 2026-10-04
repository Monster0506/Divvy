package com.monster0506.divvy.server.ledger

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class BalancesTest {
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val usd = CurrencyCode("USD")
    private val eur = CurrencyCode("EUR")
    private val householdId = Uuid.random()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(
        debtor: Uuid,
        creditor: Uuid,
        cents: Long,
        currency: CurrencyCode = usd,
        reverses: Uuid? = null,
    ) = LedgerEntry(
        id = Uuid.random(),
        householdId = householdId,
        debtorPersonId = debtor,
        creditorPersonId = creditor,
        amount = Money(cents, currency),
        sourceType = LedgerSourceType.RECEIPT,
        sourceId = Uuid.random(),
        reversesEntryId = reverses,
        createdAt = now,
    )

    @Test
    fun `no entries means no balances`() {
        assertEquals(emptyList(), balancesByCurrency(emptyList()))
    }

    @Test
    fun `a single debt is owed to the creditor`() {
        val result = balancesByCurrency(listOf(entry(debtor = a, creditor = b, cents = 500))).single()
        assertEquals(mapOf(a to -500L, b to 500L), result.netByPerson)
        assertEquals(listOf(PairDebt(a, b, 500)), result.debts)
    }

    @Test
    fun `net balances always sum to zero`() {
        val people = List(6) { Uuid.random() }
        val random = Random(42)
        val entries = List(500) {
            val debtor = people.random(random)
            val creditor = (people - debtor).random(random)
            entry(debtor, creditor, random.nextLong(1, 100_000), if (random.nextBoolean()) usd else eur)
        }
        val results = balancesByCurrency(entries)
        assertEquals(2, results.size)
        results.forEach { assertEquals(0L, it.netByPerson.values.sum(), "currency ${it.currency}") }
    }

    @Test
    fun `a reversal cancels its entry`() {
        val original = entry(debtor = a, creditor = b, cents = 500)
        val reversal = entry(debtor = b, creditor = a, cents = 500, reverses = original.id)

        val result = balancesByCurrency(listOf(original, reversal)).single()

        assertTrue(result.netByPerson.values.all { it == 0L })
        assertEquals(emptyList(), result.debts)
    }

    @Test
    fun `a settlement cancels the debt it pays`() {
        // a owes b 500; a pays b 500, which is recorded as b owing a 500.
        val result = balancesByCurrency(listOf(entry(a, b, 500), entry(b, a, 500))).single()
        assertTrue(result.netByPerson.values.all { it == 0L })
        assertEquals(emptyList(), result.debts)
    }

    @Test
    fun `opposite debts collapse to the difference`() {
        val result = balancesByCurrency(listOf(entry(a, b, 500), entry(b, a, 200))).single()
        assertEquals(listOf(PairDebt(a, b, 300)), result.debts)
        assertEquals(mapOf(a to -300L, b to 300L), result.netByPerson)
    }

    @Test
    fun `debts are reported pairwise and not simplified`() {
        val result = balancesByCurrency(listOf(entry(a, b, 100), entry(b, c, 100))).single()
        assertEquals(listOf(PairDebt(a, b, 100), PairDebt(b, c, 100)), result.debts)
        assertEquals(0L, result.netByPerson.getValue(b))
    }

    @Test
    fun `a cycle nets to zero for everyone but still shows its debts`() {
        val result = balancesByCurrency(listOf(entry(a, b, 100), entry(b, c, 100), entry(c, a, 100))).single()
        assertTrue(result.netByPerson.values.all { it == 0L })
        assertEquals(3, result.debts.size)
    }

    @Test
    fun `currencies are never mixed`() {
        val results = balancesByCurrency(listOf(entry(a, b, 500, usd), entry(b, a, 500, eur)))
        assertEquals(listOf(eur, usd), results.map { it.currency })
        assertEquals(listOf(PairDebt(b, a, 500)), results.first().debts)
        assertEquals(listOf(PairDebt(a, b, 500)), results.last().debts)
    }
}