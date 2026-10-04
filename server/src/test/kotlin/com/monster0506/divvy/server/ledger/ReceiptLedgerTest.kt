package com.monster0506.divvy.server.ledger

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.ItemOwner
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptItem
import com.monster0506.divvy.server.domain.ReceiptStatus
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ReceiptLedgerTest {
    // Fixed ids so tie-breaking (lowest id first) is predictable.
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val c = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val payer = Uuid.parse("00000000-0000-0000-0000-0000000000ff")

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val usd = CurrencyCode("USD")
    private val householdId = Uuid.random()

    private fun owner(person: Uuid, weight: Int = 1) = ItemOwner(
        receiptItemId = Uuid.random(),
        personId = person,
        shareWeight = weight,
        source = OwnershipSource.USER,
        confidence = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun item(total: Long, vararg owners: ItemOwner) = ReceiptItem(
        id = Uuid.random(),
        receiptId = Uuid.random(),
        lineNumber = 1,
        rawText = null,
        description = "item",
        quantity = BigDecimal.ONE,
        unitPrice = null,
        discount = Money(0, usd),
        total = Money(total, usd),
        owners = owners.toList(),
        createdAt = now,
        updatedAt = now,
    )

    private fun receipt(vararg items: ReceiptItem, paidBy: Uuid = payer) = Receipt(
        id = Uuid.random(),
        householdId = householdId,
        paidByPersonId = paidBy,
        merchantName = null,
        purchasedAt = now,
        subtotal = null,
        tax = null,
        total = Money(items.sumOf { it.total.amountMinor }, usd),
        status = ReceiptStatus.CONFIRMED,
        confirmedAt = now,
        items = items.toList(),
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `shares add up to each item total for any weights`() {
        val totals = listOf(0L, 1L, 7L, 99L, 100L, 101L, 1000L, 12345L, -1L, -301L)
        val weightSets = listOf(listOf(1), listOf(1, 1), listOf(1, 2), listOf(1, 1, 1), listOf(3, 5, 7), listOf(1, 1, 1, 1, 1, 1, 1))
        val people = listOf(a, b, c, Uuid.random(), Uuid.random(), Uuid.random(), Uuid.random())
        for (total in totals) for (weights in weightSets) {
            val owners = weights.mapIndexed { i, w -> owner(people[i], w) }
            val shares = receiptShares(receipt(item(total, *owners.toTypedArray())))
            assertEquals(total, shares.values.sum(), "total=$total weights=$weights")
        }
    }

    @Test
    fun `weights split proportionally`() {
        val shares = receiptShares(receipt(item(1000, owner(a, 1), owner(b, 2))))
        assertEquals(mapOf(a to 333L, b to 667L), shares)
    }

    @Test
    fun `odd cents go to the lowest person id`() {
        val shares = receiptShares(receipt(item(100, owner(a), owner(b), owner(c))))
        assertEquals(mapOf(a to 34L, b to 33L, c to 33L), shares)
    }

    @Test
    fun `result does not depend on owner order`() {
        val forward = receiptShares(receipt(item(100, owner(a), owner(b), owner(c))))
        val reversed = receiptShares(receipt(item(100, owner(c), owner(b), owner(a))))
        assertEquals(forward, reversed)
    }

    @Test
    fun `negative coupon line reduces each owner's share`() {
        val shares = receiptShares(
            receipt(
                item(1000, owner(a), owner(b)),
                item(-200, owner(a), owner(b)),
            ),
        )
        assertEquals(mapOf(a to 400L, b to 400L), shares)
    }

    @Test
    fun `negative odd-cent coupon still sums exactly`() {
        val shares = receiptShares(receipt(item(-301, owner(a), owner(b))))
        assertEquals(-301L, shares.values.sum())
    }

    @Test
    fun `unowned items are skipped`() {
        val shares = receiptShares(
            receipt(
                item(500),
                item(300, owner(a)),
            ),
        )
        assertEquals(mapOf(a to 300L), shares)
    }

    @Test
    fun `shares accumulate across items`() {
        val shares = receiptShares(
            receipt(
                item(300, owner(a)),
                item(200, owner(a), owner(b)),
            ),
        )
        assertEquals(mapOf(a to 400L, b to 100L), shares)
    }

    @Test
    fun `payer's own share produces no entry`() {
        val r = receipt(item(1000, owner(payer), owner(a)), paidBy = payer)
        val entries = ledgerEntriesFor(r)
        assertEquals(1, entries.size)
        assertEquals(a, entries.single().debtorPersonId)
        assertTrue(entries.none { it.debtorPersonId == payer })
    }

    @Test
    fun `entries point from debtor to payer with receipt as source`() {
        val r = receipt(item(1000, owner(a), owner(b)), paidBy = payer)
        val entries = ledgerEntriesFor(r).sortedBy { it.debtorPersonId.toString() }
        assertEquals(listOf(a, b), entries.map { it.debtorPersonId })
        entries.forEach {
            assertEquals(payer, it.creditorPersonId)
            assertEquals(LedgerSourceType.RECEIPT, it.sourceType)
            assertEquals(r.id, it.sourceId)
            assertEquals(r.householdId, it.householdId)
        }
        assertEquals(listOf(500L, 500L), entries.map { it.amount.amountMinor })
    }

    @Test
    fun `net-negative share writes no entry`() {
        // Current behavior: only positive shares become entries.
        val r = receipt(item(-300, owner(a)), paidBy = payer)
        assertTrue(ledgerEntriesFor(r).isEmpty())
    }
}