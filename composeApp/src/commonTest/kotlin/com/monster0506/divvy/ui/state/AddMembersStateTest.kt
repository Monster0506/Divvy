package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.CreatePersonResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs a suspend block that never actually suspends (the fakes below return immediately). */
private fun <T> runNow(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class AddMembersStateTest {
    private fun state(create: suspend (String) -> CreatePersonResult = { CreatePersonResult.Created("id-$it") }) =
        AddMembersState(create)

    private fun AddMembersState.addName(name: String) {
        onDraftChange(name)
        add()
    }

    @Test
    fun rejectsBlankAndOverlongNames() {
        assertNotNull(validateMemberName("   ", emptyList()))
        assertNotNull(validateMemberName("a".repeat(MEMBER_NAME_MAX + 1), emptyList()))
        assertNull(validateMemberName("a".repeat(MEMBER_NAME_MAX), emptyList()))
    }

    @Test
    fun rejectsDuplicatesIgnoringCaseAndSpaces() {
        assertNotNull(validateMemberName("  ANDREW ", listOf("Andrew")))
        assertNull(validateMemberName("Sam", listOf("Andrew")))
    }

    @Test
    fun addTrimsAndClearsDraft() {
        val s = state()
        s.addName("  Andrew ")
        assertEquals(listOf("Andrew"), s.rows.map { it.name })
        assertEquals("", s.draft)
    }

    @Test
    fun duplicateShowsInlineErrorAndIsNotAdded() {
        val s = state()
        s.addName("Andrew")
        s.addName("andrew")
        assertEquals(1, s.rows.size)
        assertNotNull(s.draftError)
    }

    @Test
    fun needsAtLeastTwoMembers() {
        val s = state()
        s.addName("Andrew")
        assertFalse(s.canFinish)
        s.addName("Sam")
        s.setMe("Andrew")
        assertTrue(s.canFinish)
    }

    @Test
    fun finishAddsTypedDraftAndCreatesEveryone() {
        val created = mutableListOf<String>()
        val s = state { created += it; CreatePersonResult.Created("id-$it") }
        s.addName("Andrew")
        s.setMe("Andrew")
        s.onDraftChange("Sam")
        assertTrue(s.canFinish)
        assertTrue(runNow { s.finish() })
        assertEquals(listOf("Andrew", "Sam"), created)
    }

    @Test
    fun serverRejectionShowsOnRowAndRetrySkipsCreatedPeople() {
        val calls = mutableListOf<String>()
        var rejectSam = true
        val s = state {
            calls += it
            if (it == "Sam" && rejectSam) CreatePersonResult.Invalid("Name taken") else CreatePersonResult.Created("id-$it")
        }
        s.addName("Andrew")
        s.addName("Sam")
        s.setMe("Andrew")
        assertFalse(runNow { s.finish() })
        assertEquals("Name taken", s.rows.first { it.name == "Sam" }.error)
        rejectSam = false
        assertTrue(runNow { s.finish() })
        assertEquals(listOf("Andrew", "Sam", "Sam"), calls)
    }

    @Test
    fun networkFailureShowsErrorAndKeepsList() {
        val s = state { CreatePersonResult.Failure("Can't reach the server.") }
        s.addName("Andrew")
        s.addName("Sam")
        s.setMe("Andrew")
        assertFalse(runNow { s.finish() })
        assertEquals("Can't reach the server.", s.error)
        assertEquals(2, s.rows.size)
        assertFalse(s.submitting)
    }

    @Test
    fun finish_requires_a_self_selection_and_exposes_the_selected_server_id() {
        val s = state()
        s.addName("Andrew")
        s.addName("Sam")
        assertFalse(s.canFinish)
        assertFalse(runNow { s.finish() })

        s.setMe("Sam")
        assertTrue(runNow { s.finish() })
        assertEquals("id-Sam", s.meId)
    }
}
