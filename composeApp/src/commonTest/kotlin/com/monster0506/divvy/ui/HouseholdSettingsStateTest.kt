package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.SettingsResult
import com.monster0506.divvy.data.api.CreatePersonResult
import com.monster0506.divvy.data.api.HouseholdDetails
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

class HouseholdSettingsStateTest {
    private val andrew = HouseholdMember("1", "Andrew")
    private val sam = HouseholdMember("2", "Sam")
    private val jo = HouseholdMember("3", "Jo")
    private val old = HouseholdMember("4", "Old", archived = true)

    private fun state(
        members: List<HouseholdMember> = listOf(andrew, sam, jo, old),
        rename: suspend (String) -> SettingsResult<Unit> = { SettingsResult.Ok(Unit) },
        create: suspend (String) -> CreatePersonResult = { CreatePersonResult.Created("new-$it") },
        archive: suspend (String) -> SettingsResult<Unit> = { SettingsResult.Ok(Unit) },
    ) = HouseholdSettingsState(
        load = { SettingsResult.Ok(HouseholdDetails("Apartment 4B", members)) },
        rename = rename,
        create = create,
        archive = archive,
    )

    @Test
    fun loadSplitsActiveAndArchivedMembers() {
        val s = state()
        runSettings { s.refresh() }
        assertEquals("Apartment 4B", s.name)
        assertEquals(listOf("Andrew", "Sam", "Jo"), s.members.map { it.name })
        assertEquals(listOf("Old"), s.archivedMembers.map { it.name })
    }

    @Test
    fun loadFailureShowsErrorAndKeepsScreenUnloaded() {
        val s = HouseholdSettingsState(
            load = { SettingsResult.Failure("Can't reach the server.") },
            rename = { SettingsResult.Ok(Unit) },
            create = { CreatePersonResult.Created("x") },
            archive = { SettingsResult.Ok(Unit) },
        )
        runSettings { s.refresh() }
        assertFalse(s.loaded)
        assertEquals("Can't reach the server.", s.error)
    }

    @Test
    fun renameTrimsAndUpdatesSavedName() {
        var sent: String? = null
        val s = state(rename = { sent = it; SettingsResult.Ok(Unit) })
        runSettings { s.refresh() }
        s.onNameDraftChange("  Home  ")
        assertTrue(s.canSaveName)
        assertTrue(runSettings { s.saveName() })
        assertEquals("Home", sent)
        assertEquals("Home", s.name)
        assertFalse(s.canSaveName)
    }

    @Test
    fun blankOrUnchangedNameCannotBeSaved() {
        val s = state()
        runSettings { s.refresh() }
        assertFalse(s.canSaveName)
        s.onNameDraftChange("   ")
        assertFalse(s.canSaveName)
        assertNotNull(s.nameFieldMessage)
    }

    @Test
    fun serverRejectingRenameShowsInlineMessage() {
        val s = state(rename = { SettingsResult.Invalid("Name already used") })
        runSettings { s.refresh() }
        s.onNameDraftChange("Home")
        assertFalse(runSettings { s.saveName() })
        assertEquals("Name already used", s.nameFieldMessage)
        assertEquals("Apartment 4B", s.name)
    }

    @Test
    fun archiveNeedsConfirmationThenMovesMemberToArchived() {
        val archived = mutableListOf<String>()
        val s = state(archive = { archived += it; SettingsResult.Ok(Unit) })
        runSettings { s.refresh() }
        s.requestArchive(jo)
        assertEquals(jo, s.archiving)
        assertTrue(archived.isEmpty())
        assertTrue(runSettings { s.confirmArchive() })
        assertEquals(listOf("3"), archived)
        assertEquals(listOf("Andrew", "Sam"), s.members.map { it.name })
        assertEquals(listOf("Old", "Jo"), s.archivedMembers.map { it.name })
        assertNull(s.archiving)
    }

    @Test
    fun cancelledArchiveChangesNothing() {
        val s = state()
        runSettings { s.refresh() }
        s.requestArchive(jo)
        s.cancelArchive()
        assertNull(s.archiving)
        assertEquals(3, s.members.size)
    }

    @Test
    fun cannotArchiveBelowMinimumMembers() {
        val s = state(members = listOf(andrew, sam))
        runSettings { s.refresh() }
        assertFalse(s.canArchive(andrew))
        s.requestArchive(andrew)
        assertNull(s.archiving)
    }

    @Test
    fun failedArchiveKeepsMemberActiveAndShowsError() {
        val s = state(archive = { SettingsResult.Failure("Can't reach the server.") })
        runSettings { s.refresh() }
        s.requestArchive(jo)
        assertFalse(runSettings { s.confirmArchive() })
        assertEquals(3, s.members.size)
        assertEquals("Can't reach the server.", s.error)
        assertNull(s.archiving)
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