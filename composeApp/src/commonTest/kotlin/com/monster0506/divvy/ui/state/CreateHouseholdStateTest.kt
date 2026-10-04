package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.CreateHouseholdResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateHouseholdStateTest {
    @Test
    fun validation() {
        assertNotNull(validateHouseholdName(""))
        assertNotNull(validateHouseholdName("   "))
        assertNotNull(validateHouseholdName("a".repeat(81)))
        assertNull(validateHouseholdName("a".repeat(80)))
        assertNull(validateHouseholdName("  Home  "))
    }

    @Test
    fun emptyNameShowsInlineErrorAndBlocksSubmit() = runTest {
        var calls = 0
        val state = CreateHouseholdState { calls++; CreateHouseholdResult.Created("1") }
        assertNull(state.fieldMessage) // nothing shown before the user types
        state.onNameChange("x")
        state.onNameChange("")
        assertNotNull(state.fieldMessage)
        assertFalse(state.canSubmit)
        assertNull(state.submit())
        assertEquals(0, calls)
    }

    @Test
    fun successTrimsNameAndReturnsId() = runTest {
        var sent: String? = null
        val state = CreateHouseholdState { sent = it; CreateHouseholdResult.Created("abc") }
        state.onNameChange("  Apartment 4B ")
        assertEquals("abc", state.submit())
        assertEquals("Apartment 4B", sent)
        assertFalse(state.submitting)
    }

    @Test
    fun existingHouseholdRoutesToMemberSelection() = runTest {
        val state = CreateHouseholdState { CreateHouseholdResult.Existing("172") }
        state.onNameChange("172")

        assertEquals("172", state.submit())
        assertTrue(state.joiningExisting)
    }

    @Test
    fun serverRejectionShowsInline() = runTest {
        val state = CreateHouseholdState { CreateHouseholdResult.Invalid("name taken") }
        state.onNameChange("Home")
        assertNull(state.submit())
        assertEquals("name taken", state.fieldMessage)
        state.onNameChange("Home2")
        assertNull(state.fieldMessage)
    }

    @Test
    fun failureShowsErrorAndAllowsRetry() = runTest {
        val state = CreateHouseholdState { CreateHouseholdResult.Failure("offline") }
        state.onNameChange("Home")
        assertNull(state.submit())
        assertEquals("offline", state.error)
        assertFalse(state.submitting)
        assertTrue(state.canSubmit)
    }
}
