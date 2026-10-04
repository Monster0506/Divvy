package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.NewSettlement
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.PersonPatch
import com.monster0506.divvy.server.domain.ReceiptStatus
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreRepositoryTest : RepositoryTestBase() {

    // --- household -------------------------------------------------------------------------

    @Test
    fun `household round-trips and updated_at moves on change`() = runBlocking {
        val created = repos.households.create("Apartment 4B", usd)
        assertEquals(created, repos.households.findById(created.id))

        val renamed = assertNotNull(repos.households.rename(created.id, "Apartment 5C"))

        assertEquals("Apartment 5C", renamed.name)
        assertEquals(created.createdAt, renamed.createdAt)
        assertTrue(renamed.updatedAt > created.updatedAt)
    }

    @Test
    fun `missing household returns null`() = runBlocking {
        assertNull(repos.households.findById(kotlin.uuid.Uuid.random()))
    }

    // --- person ----------------------------------------------------------------------------

    @Test
    fun `people are listed in insertion order and archiving keeps them on record`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew", "TJ")

        val archived = assertNotNull(repos.people.archive(people[2].id))

        assertNotNull(archived.archivedAt)
        assertEquals(listOf("Sumedh", "Savar", "TJ"), repos.people.listByHousehold(home.id).map { it.displayName })
        assertEquals(4, repos.people.listByHousehold(home.id, includeArchived = true).size)
    }

    @Test
    fun `the database rejects malformed venmo usernames`() = runBlocking {
        val (_, people) = household("Sumedh")
        val id = people.single().id
        assertEquals("sumedh-j", repos.people.update(id, PersonPatch(venmoUsername = "sumedh-j"))?.venmoUsername)
        assertFailsWith<ExposedSQLException> { repos.people.update(id, PersonPatch(venmoUsername = "@sumedh")) }
        assertFailsWith<ExposedSQLException> { repos.people.update(id, PersonPatch(venmoUsername = "abc")) }
        assertNull(repos.people.update(id, PersonPatch(venmoUsername = ""))?.venmoUsername)
    }

    @Test
    fun `active member names are unique per household, ignoring case`() = runBlocking {
        val (home, people) = household("Sumedh")
        assertFailsWith<ExposedSQLException> { repos.people.add(home.id, "sumedh") }

        repos.people.archive(people.single().id)
        repos.people.add(home.id, "Sumedh")
        Unit
    }

    // --- receipt ---------------------------------------------------------------------------

    @Test
    fun `receipt with items round-trips money as integer minor units`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val created = repos.receipts.create(
            NewReceipt(
                householdId = home.id,
                paidByPersonId = people[0].id,
                merchantName = "Trader Joe's",
                purchasedAt = Instant.parse("2026-09-30T17:45:00Z"),
                currency = usd,
                subtotalMinor = 1048,
                taxMinor = 52,
                totalMinor = 1100,
                items = listOf(
                    NewReceiptItem("BANANAS", totalMinor = 151, quantity = BigDecimal("2.315"), unitPriceMinor = 65),
                    NewReceiptItem("CILANTRO", totalMinor = 129),
                    NewReceiptItem("OAT MILK", totalMinor = 868),
                    NewReceiptItem("COUPON OAT MILK", totalMinor = -100),
                ),
            ),
        )

        val loaded = assertNotNull(repos.receipts.findById(created.id))
        assertEquals(created, loaded)
        assertEquals(ReceiptStatus.DRAFT, loaded.status)
        assertEquals(Money(1100, usd), loaded.total)
        assertEquals(Money(52, usd), loaded.tax)
        assertEquals(listOf(1, 2, 3, 4), loaded.items.map { it.lineNumber })
        assertEquals(listOf(151L, 129L, 868L, -100L), loaded.items.map { it.total.amountMinor })
        assertEquals(0, BigDecimal("2.315").compareTo(loaded.items[0].quantity))
        assertEquals(Money(65, usd), loaded.items[0].unitPrice)
    }

    @Test
    fun `receipts are confirmed and voided, never deleted`() = runBlocking {
        val (home, people) = household("Sumedh")
        val draft = receipt(home, people[0], "EGGS" to 499)

        val confirmed = assertNotNull(repos.receipts.confirm(draft.id))
        assertEquals(ReceiptStatus.CONFIRMED, confirmed.status)
        assertNotNull(confirmed.confirmedAt)

        val voided = assertNotNull(repos.receipts.void(draft.id))
        assertEquals(ReceiptStatus.VOID, voided.status)
        assertEquals(1, voided.items.size)
        assertEquals(listOf(draft.id), repos.receipts.listByHousehold(home.id).map { it.id })
    }

    @Test
    fun `receipts list newest first`() = runBlocking {
        val (home, people) = household("Sumedh")
        val older = receipt(home, people[0], "EGGS" to 499)
        val newer = repos.receipts.create(
            NewReceipt(home.id, people[0].id, null, Instant.parse("2026-10-02T09:00:00Z"), usd, null, null, 349, emptyList()),
        )

        assertEquals(listOf(newer.id, older.id), repos.receipts.listByHousehold(home.id).map { it.id })
    }

    @Test
    fun `receipt payer must belong to the receipt's household`() = runBlocking {
        val (home, _) = household("Sumedh")
        val (_, strangers) = household("Stranger")

        assertFailsWith<ExposedSQLException> { receipt(home, strangers.single(), "EGGS" to 499) }
        assertEquals(emptyList(), repos.receipts.listByHousehold(home.id))
    }

    @Test
    fun `a failed item insert rolls back the whole receipt`() = runBlocking {
        val (home, people) = household("Sumedh")

        assertFailsWith<ExposedSQLException> {
            repos.receipts.create(
                NewReceipt(
                    home.id, people[0].id, null, Instant.now(), usd, null, null, 100,
                    listOf(NewReceiptItem("OK", 100), NewReceiptItem("   ", 0)),
                ),
            )
        }
        assertEquals(emptyList(), repos.receipts.listByHousehold(home.id))
    }

    @Test
    fun `a receipt is written with its items and owners in one transaction`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew")
        val (sumedh, savar, andrew) = people
        val created = repos.receipts.create(
            NewReceipt(
                home.id, sumedh.id, "Kroger", Instant.parse("2026-10-01T12:00:00Z"), usd, null, null, 1149,
                listOf(
                    NewReceiptItem(
                        "Coke Zero 12pk", 699, rawText = "COKE ZERO 12PK  7.99", discountMinor = 100,
                        owners = listOf(OwnerShare(savar.id)),
                    ),
                    NewReceiptItem("Paper towels", 450, owners = people.map { OwnerShare(it.id) }),
                ),
            ),
        )

        val loaded = assertNotNull(repos.receipts.findById(created.id))
        assertEquals(created, loaded)
        val (coke, towels) = loaded.items
        assertEquals("COKE ZERO 12PK  7.99", coke.rawText)
        assertEquals(Money(100, usd), coke.discount)
        assertEquals(listOf(savar.id), coke.owners.map { it.personId })
        assertEquals(setOf(sumedh.id, savar.id, andrew.id), towels.owners.map { it.personId }.toSet())
        assertEquals(Money(0, usd), towels.discount)
        assertNull(towels.rawText)
    }

    @Test
    fun `a bad owner rolls back the receipt and its items`() = runBlocking {
        val (home, people) = household("Sumedh")
        val (_, strangers) = household("Stranger")

        assertFailsWith<ExposedSQLException> {
            repos.receipts.create(
                NewReceipt(
                    home.id, people[0].id, null, Instant.now(), usd, null, null, 100,
                    listOf(NewReceiptItem("MILK", 100, owners = listOf(OwnerShare(strangers.single().id)))),
                ),
            )
        }
        assertEquals(emptyList(), repos.receipts.listByHousehold(home.id))
    }

    // --- settlement ------------------------------------------------------------------------

    @Test
    fun `settlements are recorded between members of the same household`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val (sumedh, savar) = people

        val recorded = repos.settlements.record(
            NewSettlement(home.id, savar.id, sumedh.id, Money(2350, usd), note = "Venmo"),
        )

        assertEquals(recorded, repos.settlements.findById(recorded.id))
        assertEquals(Money(2350, usd), recorded.amount)
        assertEquals(listOf(recorded.id), repos.settlements.listByHousehold(home.id).map { it.id })
    }

    @Test
    fun `settlement parties must belong to the household`() = runBlocking {
        val (home, people) = household("Sumedh")
        val (_, strangers) = household("Stranger")

        assertFailsWith<ExposedSQLException> {
            repos.settlements.record(NewSettlement(home.id, strangers.single().id, people.single().id, Money(100, usd)))
        }
        Unit
    }

    @Test
    fun `settlement amounts must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            NewSettlement(kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random(), Money(0, usd))
        }
    }

    // --- integrity -------------------------------------------------------------------------

    @Test
    fun `a person with history cannot be hard-deleted`() = runBlocking {
        val (home, people) = household("Sumedh")
        receipt(home, people[0], "EGGS" to 499)

        assertFailsWith<Exception> {
            TestDatabase.execute("DELETE FROM person WHERE id = '${people[0].id}'")
        }
        assertNotNull(repos.people.findById(people[0].id))
        Unit
    }

    @Test
    fun `deleting a household removes all of its data`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val item = receipt(home, people[0], "EGGS" to 499).items.single()
        repos.itemOwners.assignToAllMembers(item.id)
        repos.settlements.record(NewSettlement(home.id, people[1].id, people[0].id, Money(250, usd)))

        val (otherHome, _) = household("Stranger")

        assertTrue(repos.households.delete(home.id))

        assertNull(repos.households.findById(home.id))
        assertNotNull(repos.households.findById(otherHome.id))
        listOf("receipt", "receipt_item", "item_owner", "settlement").forEach { table ->
            assertEquals(0L, TestDatabase.query("SELECT count(*) AS n FROM $table").single()["n"], table)
        }
        assertEquals(1L, TestDatabase.query("SELECT count(*) AS n FROM person").single()["n"])
        // The ownership log outlives the data it describes: 2 assignments + 2 cascaded unassignments.
        assertEquals(4L, TestDatabase.query("SELECT count(*) AS n FROM item_owner_history").single()["n"])
    }

    @Test
    fun `currency codes are validated`() {
        assertFailsWith<IllegalArgumentException> { CurrencyCode("usd") }
        assertFailsWith<IllegalArgumentException> { CurrencyCode("US") }
    }
}