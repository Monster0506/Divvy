package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipChange
import com.monster0506.divvy.server.domain.OwnershipSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ItemOwnerRepositoryTest : RepositoryTestBase() {

    @Test
    fun `an item can be owned by one person`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew", "TJ")
        val tj = people[3]
        val item = receipt(home, tj, "COKE ZERO 12PK" to 699).items.single()

        val owners = repos.itemOwners.setOwners(item.id, listOf(OwnerShare(tj.id)))

        assertEquals(listOf(tj.id), owners.map { it.personId })
        assertEquals(listOf(tj.id), repos.receipts.findById(item.receiptId)!!.items.single().owners.map { it.personId })
    }

    @Test
    fun `an item can be owned by several people`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew", "TJ")
        val (sumedh, savar) = people
        val item = receipt(home, sumedh, "CILANTRO" to 129).items.single()

        val owners = repos.itemOwners.setOwners(item.id, listOf(OwnerShare(sumedh.id), OwnerShare(savar.id)))

        assertEquals(setOf(sumedh.id, savar.id), owners.map { it.personId }.toSet())
    }

    @Test
    fun `an item can be owned by everyone in the household`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew", "TJ")
        val item = receipt(home, people[0], "PAPER TOWELS" to 1299).items.single()

        val owners = repos.itemOwners.assignToAllMembers(item.id)

        assertEquals(people.map { it.id }.toSet(), owners.map { it.personId }.toSet())
        assertTrue(owners.all { it.shareWeight == 1 })
    }

    @Test
    fun `assigning to everyone skips archived members and snapshots membership`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew")
        repos.people.archive(people[2].id)
        val item = receipt(home, people[0], "EGGS" to 499).items.single()

        repos.itemOwners.assignToAllMembers(item.id)
        repos.people.add(home.id, "TJ")

        assertEquals(setOf(people[0].id, people[1].id), repos.itemOwners.ownersOf(item.id).map { it.personId }.toSet())
    }

    @Test
    fun `setting owners replaces the previous set and supports uneven shares`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar", "Andrew")
        val (sumedh, savar, andrew) = people
        val item = receipt(home, sumedh, "RICE 10LB" to 1800).items.single()
        repos.itemOwners.assignToAllMembers(item.id)

        val owners = repos.itemOwners.setOwners(item.id, listOf(OwnerShare(sumedh.id, shareWeight = 2), OwnerShare(andrew.id)))

        assertEquals(mapOf(sumedh.id to 2, andrew.id to 1), owners.associate { it.personId to it.shareWeight })
        assertTrue(savar.id !in owners.map { it.personId })
    }

    @Test
    fun `an empty owner list unassigns the item`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val item = receipt(home, people[0], "MILK" to 399).items.single()
        repos.itemOwners.assignToAllMembers(item.id)

        assertEquals(emptyList(), repos.itemOwners.setOwners(item.id, emptyList()))
    }

    @Test
    fun `a person from another household cannot own an item`() = runBlocking {
        val (home, people) = household("Sumedh")
        val (_, strangers) = household("Stranger")
        val item = receipt(home, people[0], "BREAD" to 349).items.single()

        assertFailsWith<ExposedSQLException> {
            repos.itemOwners.setOwners(item.id, listOf(OwnerShare(strangers.single().id)))
        }
        assertEquals(emptyList(), repos.itemOwners.ownersOf(item.id))
    }

    @Test
    fun `duplicate owners are rejected`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh")
        val item = receipt(home, people[0], "BREAD" to 349).items.single()

        assertFailsWith<IllegalArgumentException> {
            repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[0].id), OwnerShare(people[0].id)))
        }
    }

    @Test
    fun `ownership changes are kept as history for learning`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val (sumedh, savar) = people
        val item = receipt(home, sumedh, "SERRANO PEPPERS" to 89).items.single()

        // The model predicts Savar; a person corrects it to Sumedh.
        repos.itemOwners.setOwners(
            item.id,
            listOf(OwnerShare(savar.id, source = OwnershipSource.MODEL, confidence = BigDecimal("0.6100"))),
        )
        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(sumedh.id)))
        // Re-saving an unchanged assignment does not add noise to the log.
        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(sumedh.id)))

        val history = repos.itemOwners.history(item.id)
        assertEquals(
            listOf(
                Triple(savar.id, OwnershipChange.ASSIGNED, OwnershipSource.MODEL),
                Triple(savar.id, OwnershipChange.UNASSIGNED, OwnershipSource.MODEL),
                Triple(sumedh.id, OwnershipChange.ASSIGNED, OwnershipSource.USER),
            ),
            history.map { Triple(it.personId, it.change, it.source) },
        )
        assertEquals(0, BigDecimal("0.61").compareTo(history.first().confidence))
    }

    @Test
    fun `assigning an unknown item fails`() = runBlocking {
        assertFailsWith<NoSuchElementException> {
            repos.itemOwners.setOwners(kotlin.uuid.Uuid.random(), emptyList())
        }
        Unit
    }
}