package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.LedgerEntry
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Confirming, reopening and voiding a receipt keep the ledger in step with it (issue #88). */
class ReceiptLedgerIntegrationTest : RepositoryTestBase() {

    private suspend fun assign(item: ReceiptItem, vararg owners: Person) {
        repos.itemOwners.setOwners(item.id, owners.map { OwnerShare(it.id) })
    }

    private suspend fun entriesOf(receiptId: Uuid): List<LedgerEntry> =
        repos.ledger.listBySource(LedgerSourceType.RECEIPT, receiptId)

    /** What each person is owed (positive) or owes (negative) according to [entries]. */
    private fun net(entries: List<LedgerEntry>): Map<Uuid, Long> {
        val result = mutableMapOf<Uuid, Long>()
        entries.forEach {
            result.merge(it.creditorPersonId, it.amount.amountMinor, Long::plus)
            result.merge(it.debtorPersonId, -it.amount.amountMinor, Long::plus)
        }
        return result
    }

    @Test
    fun `confirming writes one entry per non-payer share`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh", "Alex")
        val (payer, sumedh, alex) = people
        val receipt = receipt(home, payer, "EGGS" to 500, "MILK" to 300)
        assign(receipt.items[0], payer, sumedh) // eggs split between payer and Sumedh
        assign(receipt.items[1], alex) // Alex owns the milk

        repos.receipts.confirm(receipt.id)

        val entries = entriesOf(receipt.id)
        assertEquals(2, entries.size)
        assertEquals(
            mapOf(sumedh.id to 250L, alex.id to 300L),
            entries.associate { it.debtorPersonId to it.amount.amountMinor },
        )
        assertTrue(entries.all { it.creditorPersonId == payer.id })
        assertTrue(entries.all { it.reversesEntryId == null })
        Unit
    }

    @Test
    fun `the payer's own items and unowned items create no entries`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val (payer, _) = people
        val receipt = receipt(home, payer, "EGGS" to 500, "MILK" to 300)
        assign(receipt.items[0], payer) // only the payer owns the eggs; the milk has no owner

        repos.receipts.confirm(receipt.id)

        assertEquals(emptyList(), entriesOf(receipt.id))
        Unit
    }

    @Test
    fun `confirming twice does not write entries twice`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val receipt = receipt(home, people[0], "EGGS" to 500)
        assign(receipt.items[0], people[1])

        repos.receipts.confirm(receipt.id)
        repos.receipts.confirm(receipt.id)

        assertEquals(1, entriesOf(receipt.id).size)
        Unit
    }

    @Test
    fun `confirm then reopen nets to zero`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh", "Alex")
        val receipt = receipt(home, people[0], "EGGS" to 500, "MILK" to 300)
        assign(receipt.items[0], people[1])
        assign(receipt.items[1], people[2])
        repos.receipts.confirm(receipt.id)

        val reopened = assertNotNull(repos.receipts.reopen(receipt.id))

        assertEquals(ReceiptStatus.DRAFT, reopened.status)
        assertNull(reopened.confirmedAt)
        val entries = entriesOf(receipt.id)
        assertEquals(4, entries.size) // two originals and two reversals
        assertTrue(net(entries).values.all { it == 0L }, "net balances were ${net(entries)}")
        Unit
    }

    @Test
    fun `reopening twice does not reverse twice`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val receipt = receipt(home, people[0], "EGGS" to 500)
        assign(receipt.items[0], people[1])
        repos.receipts.confirm(receipt.id)

        repos.receipts.reopen(receipt.id)
        repos.receipts.reopen(receipt.id)

        assertEquals(2, entriesOf(receipt.id).size)
        Unit
    }

    @Test
    fun `reopening a draft does nothing`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val receipt = receipt(home, people[0], "EGGS" to 500)
        assign(receipt.items[0], people[1])

        val result = assertNotNull(repos.receipts.reopen(receipt.id))

        assertEquals(ReceiptStatus.DRAFT, result.status)
        assertEquals(emptyList(), entriesOf(receipt.id))
        Unit
    }

    @Test
    fun `confirm, edit and reconfirm leaves only the new shares`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh", "Alex")
        val (payer, sumedh, alex) = people
        val receipt = receipt(home, payer, "EGGS" to 500, "MILK" to 300)
        assign(receipt.items[0], sumedh)
        assign(receipt.items[1], alex)
        repos.receipts.confirm(receipt.id)

        repos.receipts.reopen(receipt.id)
        assign(receipt.items[1], sumedh) // correction: the milk was Sumedh's, not Alex's
        repos.receipts.confirm(receipt.id)

        val balances = net(entriesOf(receipt.id))
        assertEquals(800L, balances[payer.id])
        assertEquals(-800L, balances[sumedh.id])
        assertEquals(0L, balances[alex.id] ?: 0L)
        assertEquals(0L, balances.values.sum())
        Unit
    }

    @Test
    fun `voiding a confirmed receipt cancels its entries once`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val receipt = receipt(home, people[0], "EGGS" to 500)
        assign(receipt.items[0], people[1])
        repos.receipts.confirm(receipt.id)

        repos.receipts.void(receipt.id)
        repos.receipts.void(receipt.id)

        val entries = entriesOf(receipt.id)
        assertEquals(2, entries.size)
        assertTrue(net(entries).values.all { it == 0L })
        Unit
    }

    @Test
    fun `voiding a draft writes nothing`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh")
        val receipt = receipt(home, people[0], "EGGS" to 500)

        repos.receipts.void(receipt.id)

        assertEquals(emptyList(), entriesOf(receipt.id))
        Unit
    }

    @Test
    fun `odd cents are split without losing or inventing money`() = runBlocking {
        val (home, people) = household("Savar", "Sumedh", "Alex")
        val (payer, sumedh, alex) = people
        val receipt = receipt(home, payer, "SNACKS" to 100)
        assign(receipt.items[0], payer, sumedh, alex) // 100 cents three ways

        repos.receipts.confirm(receipt.id)

        val owed = entriesOf(receipt.id).sumOf { it.amount.amountMinor }
        // The payer's own share is not an entry, so the rest is 100 minus the payer's share.
        assertTrue(owed in 66L..67L, "others owe $owed")
        Unit
    }
}