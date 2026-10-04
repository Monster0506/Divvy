package com.monster0506.divvy.server.repository

import com.monster0506.divvy.server.domain.LabelSource
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabelSourceTest : RepositoryTestBase() {
    private fun predicted(personId: kotlin.uuid.Uuid) =
        OwnerShare(personId, source = OwnershipSource.MODEL, confidence = BigDecimal("0.9000"))

    @Test
    fun `items are unlabelled until the receipt is confirmed`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val draft = receipt(home, people[0], "MILK" to 400)
        assertNull(draft.items.single().labelSource)
    }

    @Test
    fun `every confirmed item gets a source`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val (sumedh, savar) = people
        val draft = receipt(home, sumedh, "MILK" to 400, "EGGS" to 500, "PEPPERS" to 90, "BREAD" to 300)
        val (milk, eggs, peppers, bread) = draft.items

        repos.itemOwners.setOwners(milk.id, listOf(OwnerShare(sumedh.id)))
        // Predicted for Savar and kept as is.
        repos.itemOwners.setOwners(eggs.id, listOf(predicted(savar.id)))
        // Predicted for Savar, then moved to Sumedh.
        repos.itemOwners.setOwners(peppers.id, listOf(predicted(savar.id)))
        repos.itemOwners.setOwners(peppers.id, listOf(OwnerShare(sumedh.id)))
        // Nobody picked an owner for bread.

        val confirmed = repos.receipts.confirm(draft.id)!!

        assertEquals(
            listOf(
                LabelSource.MANUAL,
                LabelSource.PREDICTED_ACCEPTED,
                LabelSource.PREDICTED_CORRECTED,
                LabelSource.MANUAL,
            ),
            confirmed.items.map { it.labelSource },
        )
        assertEquals(confirmed.items.map { it.labelSource }, repos.receipts.findById(draft.id)!!.items.map { it.labelSource })
    }

    @Test
    fun `accepting a prediction through the user path still counts as accepted`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val draft = receipt(home, people[0], "EGGS" to 500)
        val item = draft.items.single()

        repos.itemOwners.setOwners(item.id, listOf(predicted(people[1].id)))
        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[1].id)))

        assertEquals(LabelSource.PREDICTED_ACCEPTED, repos.receipts.confirm(draft.id)!!.items.single().labelSource)
    }

    @Test
    fun `changing only the weights counts as a correction`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val draft = receipt(home, people[0], "PIZZA" to 1800)
        val item = draft.items.single()

        repos.itemOwners.setOwners(item.id, listOf(predicted(people[0].id), predicted(people[1].id)))
        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[0].id, 2), OwnerShare(people[1].id)))

        assertEquals(LabelSource.PREDICTED_CORRECTED, repos.receipts.confirm(draft.id)!!.items.single().labelSource)
    }

    @Test
    fun `correction rate counts only predicted items`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val (sumedh, savar) = people
        assertNull(repos.receipts.correctionRate(home.id))

        val draft = receipt(home, sumedh, "MILK" to 400, "EGGS" to 500, "PEPPERS" to 90, "BREAD" to 300)
        val (milk, eggs, peppers, bread) = draft.items
        repos.itemOwners.setOwners(milk.id, listOf(OwnerShare(sumedh.id)))
        repos.itemOwners.setOwners(eggs.id, listOf(predicted(savar.id)))
        repos.itemOwners.setOwners(bread.id, listOf(predicted(savar.id)))
        repos.itemOwners.setOwners(bread.id, listOf(OwnerShare(sumedh.id)))
        repos.itemOwners.setOwners(peppers.id, listOf(predicted(savar.id)))
        repos.itemOwners.setOwners(peppers.id, listOf(OwnerShare(sumedh.id)))
        repos.receipts.confirm(draft.id)

        assertEquals(2.0 / 3.0, repos.receipts.correctionRate(home.id)!!, 1e-9)
    }

    @Test
    fun `confirming twice does not relabel`() = runBlocking {
        val (home, people) = household("Sumedh", "Savar")
        val draft = receipt(home, people[0], "EGGS" to 500)
        val item = draft.items.single()
        repos.itemOwners.setOwners(item.id, listOf(predicted(people[1].id)))
        repos.receipts.confirm(draft.id)

        repos.itemOwners.setOwners(item.id, listOf(OwnerShare(people[0].id)))
        repos.receipts.confirm(draft.id)

        assertEquals(LabelSource.PREDICTED_ACCEPTED, repos.receipts.findById(draft.id)!!.items.single().labelSource)
    }
}
