package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.HouseholdMember
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun <T> runJoinNow(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class JoinHouseholdStateTest {
    @Test
    fun loads_existing_members_and_only_allows_selecting_a_member_from_that_household() {
        val state = JoinHouseholdState("172") { id ->
            assertEquals("172", id)
            listOf(HouseholdMember("p1", "TJ"), HouseholdMember("p2", "Sam"))
        }
        runJoinNow { state.load() }

        assertFalse(state.canContinue)
        state.select("unknown")
        assertFalse(state.canContinue)
        state.select("p2")
        assertTrue(state.canContinue)
        assertEquals("p2", state.selectedId)
    }

    @Test
    fun archived_members_are_not_offered_and_empty_households_explain_why() {
        val state = JoinHouseholdState("172") { listOf(HouseholdMember("p1", "TJ", archived = true)) }
        runJoinNow { state.load() }

        assertTrue(state.members.isEmpty())
        assertFalse(state.canContinue)
        assertTrue(state.message!!.contains("no members yet"))
    }
}
