package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewLedgerEntry
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LedgerRepositoryTest : RepositoryTestBase() {

    @Test
    fun `entries round-trip and list oldest first`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val receipt = receipt(home, people[0], "EGGS" to 500)

        val first = repos.ledger.append(NewLedgerEntry(home.id, people[1].id, people[0].id, Money(250, usd), LedgerSourceType.RECEIPT, receipt.id))
        val second = repos.ledger.append(NewLedgerEntry(home.id, people[0].id, people[1].id, Money(100, usd), LedgerSourceType.SETTLEMENT, Uuid.random()))

        assertEquals(first, repos.ledger.findById(first.id))
        assertEquals(Money(250, usd), first.amount)
        assertNull(first.reversesEntryId)
        assertEquals(listOf(first.id, second.id), repos.ledger.listByHousehold(home.id).map { it.id })
        assertEquals(listOf(first.id), repos.ledger.listBySource(LedgerSourceType.RECEIPT, receipt.id).map { it.id })
    }

    @Test
    fun `reversing an entry appends a mirrored entry`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val sourceId = Uuid.random()
        val original = repos.ledger.append(NewLedgerEntry(home.id, people[1].id, people[0].id, Money(250, usd), LedgerSourceType.RECEIPT, sourceId))

        val reversal = assertNotNull(repos.ledger.reverse(original.id))

        assertEquals(original.id, reversal.reversesEntryId)
        assertEquals(original.creditorPersonId, reversal.debtorPersonId)
        assertEquals(original.debtorPersonId, reversal.creditorPersonId)
        assertEquals(original.amount, reversal.amount)
        assertEquals(listOf(original, reversal), repos.ledger.listBySource(LedgerSourceType.RECEIPT, sourceId))
        assertNull(repos.ledger.reverse(Uuid.random()))
        assertFailsWith<ExposedSQLException> { repos.ledger.reverse(original.id) }
        Unit
    }

    @Test
    fun `existing rows cannot be updated`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val entry = repos.ledger.append(NewLedgerEntry(home.id, people[1].id, people[0].id, Money(250, usd), LedgerSourceType.RECEIPT, Uuid.random()))

        assertFailsWith<Exception> { TestDatabase.execute("UPDATE ledger_entry SET amount_minor = 1 WHERE id = '${entry.id}'") }
        assertEquals(entry, repos.ledger.findById(entry.id))
        Unit
    }

    @Test
    fun `parties must belong to the household`() = runBlocking {
        val (home, people) = household("Sumedh")
        val (_, strangers) = household("Stranger")

        assertFailsWith<ExposedSQLException> {
            repos.ledger.append(NewLedgerEntry(home.id, strangers.single().id, people.single().id, Money(100, usd), LedgerSourceType.RECEIPT, Uuid.random()))
        }
        Unit
    }

    @Test
    fun `invalid entries are rejected`() {
        val id = Uuid.random()
        assertFailsWith<IllegalArgumentException> { NewLedgerEntry(id, id, id, Money(100, usd), LedgerSourceType.RECEIPT, id) }
        assertFailsWith<IllegalArgumentException> { NewLedgerEntry(id, id, Uuid.random(), Money(0, usd), LedgerSourceType.RECEIPT, id) }
    }

    @Test
    fun `deleting a household removes its ledger entries`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val entry = repos.ledger.append(NewLedgerEntry(home.id, people[1].id, people[0].id, Money(250, usd), LedgerSourceType.RECEIPT, Uuid.random()))
        repos.ledger.reverse(entry.id)

        repos.households.delete(home.id)

        assertEquals(emptyList(), repos.ledger.listByHousehold(home.id))
    }
}
