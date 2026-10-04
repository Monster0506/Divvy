package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.OwnerShare
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchivedPeopleTest : RepositoryTestBase() {
    @Test
    fun `archived people are hidden unless includeArchived is set`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar")
        repos.people.archive(people[1].id)

        assertEquals(listOf("Sumedh"), repos.people.listByHousehold(home.id).map { it.displayName })
        assertEquals(
            setOf("Sumedh", "Savar"),
            repos.people.listByHousehold(home.id, includeArchived = true).map { it.displayName }.toSet(),
        )
    }

    @Test
    fun `archived people cannot be assigned new items`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar")
        val item = receipt(home, people[0], "CILANTRO" to 129).items.single()
        repos.people.archive(people[1].id)

        assertFailsWith<ArchivedPersonException> {
            repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[0].id), OwnerShare(people[1].id)))
        }
        assertTrue(repos.itemOwners.ownersOf(item.id).isEmpty())
    }

    @Test
    fun `assigning to everyone skips archived people`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar", "Andrew")
        val item = receipt(home, people[0], "PAPER TOWELS" to 1299).items.single()
        repos.people.archive(people[2].id)

        val owners = repos.itemOwners.assignToAllMembers(item.id)

        assertEquals(setOf(people[0].id, people[1].id), owners.map { it.personId }.toSet())
    }

    @Test
    fun `historical receipts still show archived owners`() = runBlocking<Unit> {
        val (home, people) = household("Sumedh", "Savar")
        val created = receipt(home, people[0], "CILANTRO" to 129)
        val item = created.items.single()
        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[0].id), OwnerShare(people[1].id)))
        repos.people.archive(people[1].id)

        val reloaded = repos.receipts.findById(created.id)!!
        assertEquals(setOf(people[0].id, people[1].id), reloaded.items.single().owners.map { it.personId }.toSet())

        // An archived person who already owns the item may stay on it when the shares change.
        val updated = repos.itemOwners.setOwners(
            item.id,
            listOf(OwnerShare(people[0].id), OwnerShare(people[1].id, shareWeight = 2)),
        )
        assertEquals(2, updated.size)
    }
}