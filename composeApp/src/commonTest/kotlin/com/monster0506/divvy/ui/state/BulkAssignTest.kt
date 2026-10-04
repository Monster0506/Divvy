package com.monster0506.divvy.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkAssignTest {
    private fun DivvyState.owners(id: Int) = draft.lines.first { it.id == id }.owners

    @Test
    fun assignRemainingGivesPendingLinesToOnePersonAndConfirmsThem() {
        val state = DivvyState()
        val pending = state.draft.lines.filter { !it.confirmed }.map { it.id }
        assertTrue(pending.isNotEmpty())
        val untouched = state.draft.lines.filter { it.confirmed }.associate { it.id to it.owners }

        state.assignRemaining("su")

        pending.forEach { assertEquals(setOf("su"), state.owners(it)) }
        assertTrue(state.draft.lines.all { it.confirmed })
        untouched.forEach { (id, owners) -> assertEquals(owners, state.owners(id)) }
    }

    @Test
    fun toggleOwnerOnSelectedAddsThenRemovesForAllSelectedLines() {
        val state = DivvyState()
        state.toggleSelecting()
        state.toggleSelected(6)
        state.toggleSelected(7)

        state.toggleOwnerOnSelected("tj")
        assertTrue("tj" in state.owners(6) && "tj" in state.owners(7))
        assertTrue(state.draft.lines.first { it.id == 6 }.confirmed)

        state.toggleOwnerOnSelected("tj")
        assertFalse("tj" in state.owners(6) || "tj" in state.owners(7))
        assertEquals(setOf("su", "sv"), state.owners(6))
    }

    @Test
    fun toggleOwnerOnSelectedIgnoresLinesOutsideTheSelection() {
        val state = DivvyState()
        val before = state.owners(8)
        state.toggleSelecting()
        state.toggleSelected(6)
        state.toggleOwnerOnSelected("tj")
        assertEquals(before, state.owners(8))
    }

    @Test
    fun leavingSelectModeClearsSelection() {
        val state = DivvyState()
        state.toggleSelecting()
        state.toggleSelected(1)
        state.toggleSelecting()
        assertTrue(state.selected.isEmpty())
        assertFalse(state.selecting)
    }

    @Test
    fun unassignedFilterTogglesAndResetsAfterAssigningRemaining() {
        val state = DivvyState()
        state.toggleUnassignedOnly()
        assertTrue(state.unassignedOnly)
        state.assignRemaining("an")
        assertFalse(state.unassignedOnly)
    }
}
