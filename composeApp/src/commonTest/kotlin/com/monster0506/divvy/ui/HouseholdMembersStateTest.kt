package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdMember
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
private fun <T> runSettings(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class HouseholdMembersStateTest {
    private val andrew = HouseholdMember("1", "Andrew")
    private val sam = HouseholdMember("2", "Sam")
    private val jo = HouseholdMember("3", "Jo")
    private val old = HouseholdMember("4", "Old", archived = true)

    private fun state(
        members: List<HouseholdMember> = listOf(andrew, sam, jo, old),
        create: suspend (String) -> CreatePersonResult = { CreatePersonResult.Created("new-$it") },
        setVenmo: suspend (String, String) -> SettingsResult<String?> = { _, u -> SettingsResult.Ok(u.ifEmpty { null }) },
    ) = HouseholdMembersState(
        load = { SettingsResult.Ok(members) },
        create = create,
        setVenmo = setVenmo,
    )

    @Test
    fun venmoUsernameIsSavedWithoutTheAtSign() {
        val sent = mutableListOf<Pair<String, String>>()
        val s = state(setVenmo = { id, u -> sent += id to u; SettingsResult.Ok(u) })
        runSettings { s.refresh() }
        s.editVenmo(sam)
        s.onVenmoDraftChange(" @sam-pays ")
        assertNull(s.venmoMessage)
        assertTrue(runSettings { s.saveVenmo() })
        assertEquals(listOf("2" to "sam-pays"), sent)
        assertEquals("sam-pays", s.members.first { it.id == "2" }.venmoUsername)
        assertNull(s.editingVenmo)
    }

    @Test
    fun invalidVenmoUsernameIsNotSent() {
        var calls = 0
        val s = state(setVenmo = { _, u -> calls++; SettingsResult.Ok(u) })
        runSettings { s.refresh() }
        s.editVenmo(sam)
        s.onVenmoDraftChange("sam")
        assertEquals("Venmo usernames are at least 5 characters.", s.venmoMessage)
        assertFalse(runSettings { s.saveVenmo() })
        assertEquals(0, calls)
        assertNotNull(s.editingVenmo)
    }

    @Test
    fun clearingVenmoUsernameRemovesIt() {
        val s = state(members = listOf(andrew, sam.copy(venmoUsername = "sam-pays"), jo))
        runSettings { s.refresh() }
        s.editVenmo(s.members.first { it.id == "2" })
        assertEquals("sam-pays", s.venmoDraft)
        s.onVenmoDraftChange("")
        assertTrue(runSettings { s.saveVenmo() })
        assertNull(s.members.first { it.id == "2" }.venmoUsername)
    }

    @Test
    fun serverRejectionKeepsTheDialogOpenWithItsMessage() {
        val s = state(setVenmo = { _, _ -> SettingsResult.Invalid("venmoUsername must be 5 to 30 letters") })
        runSettings { s.refresh() }
        s.editVenmo(sam)
        s.onVenmoDraftChange("sam-pays")
        assertFalse(runSettings { s.saveVenmo() })
        assertEquals("venmoUsername must be 5 to 30 letters", s.venmoMessage)
        assertNotNull(s.editingVenmo)
        assertNull(s.members.first { it.id == "2" }.venmoUsername)
    }

    @Test
    fun loadSplitsActiveAndArchivedMembers() {
        val s = state()
        runSettings { s.refresh() }
        assertEquals(listOf("Andrew", "Sam", "Jo"), s.members.map { it.name })
        assertEquals(listOf("Old"), s.archivedMembers.map { it.name })
    }

    @Test
    fun loadFailureShowsErrorAndStaysUnloaded() {
        val s = HouseholdMembersState(
            load = { SettingsResult.Failure("Can't reach the server.") },
            create = { CreatePersonResult.Created("x") },
        )
        runSettings { s.refresh() }
        assertFalse(s.loaded)
        assertEquals("Can't reach the server.", s.error)
    }

    @Test
    fun addMemberRejectsDuplicatesAndAppendsNewPeople() {
        val s = state()
        runSettings { s.refresh() }
        s.onMemberDraftChange("sam")
        assertFalse(runSettings { s.addMember() })
        assertNotNull(s.memberError)
        s.onMemberDraftChange("Kim")
        assertTrue(runSettings { s.addMember() })
        assertEquals("Kim", s.members.last().name)
        assertEquals("", s.memberDraft)
    }
}
