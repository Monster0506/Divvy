package com.monster0506.divvy.settings

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.dto.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses an unconfined scope and no debounce, with fakes that never suspend, so everything runs inline. */
class MemberSyncControllerTest {
    private var applied: List<HouseholdMember> = emptyList()
    private val names = mutableMapOf<String, String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val archived = mutableListOf<String>()

    private fun controller(
        list: suspend (String) -> List<HouseholdMember> = {
            listOf(HouseholdMember("p1", "Andrew"), HouseholdMember("p2", "Sam"), HouseholdMember("p3", "Jo"))
        },
        rename: suspend (String, String, String) -> HouseholdMember = { _, id, name ->
            renamed += id to name
            HouseholdMember(id, name)
        },
        archive: suspend (String, String) -> Unit = { _, id -> archived += id },
    ) = MemberSyncController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        listMembers = list,
        renameMember = rename,
        archiveMember = archive,
        applyMembers = { applied = it },
        applyName = { id, name -> names[id] = name },
        debounceMillis = 0,
    )

    private fun refused(message: String) = ApiException(ApiFailure.Validation(ApiError("conflict", message)))

    @Test
    fun loadAppliesTheServerMembers() {
        val c = controller()
        c.load("h1")
        assertEquals(listOf("Andrew", "Sam", "Jo"), applied.map { it.name })
    }

    @Test
    fun renameSavesTheTrimmedName() {
        val c = controller()
        c.load("h1")
        c.rename("p2", "  Samuel ")
        assertEquals(listOf("p2" to "Samuel"), renamed)
        assertNull(c.message)
    }

    @Test
    fun refusedRenameRevertsWithTheServerMessage() {
        val c = controller(rename = { _, _, _ -> throw refused("Name already used") })
        c.load("h1")
        c.rename("p2", "Andrew")
        assertEquals("Sam", names["p2"])
        assertEquals("Name already used", c.message)
    }

    @Test
    fun blankNameIsNotSentAndIsReverted() {
        val c = controller()
        c.load("h1")
        c.rename("p2", "   ")
        assertTrue(renamed.isEmpty())
        assertEquals("Sam", names["p2"])
        assertNotNull(c.message)
    }

    @Test
    fun unchangedNameIsNotSent() {
        val c = controller()
        c.load("h1")
        c.rename("p2", "Sam ")
        assertTrue(renamed.isEmpty())
    }

    @Test
    fun archiveReloadsTheListAndReportsDone() {
        var members = listOf(HouseholdMember("p1", "Andrew"), HouseholdMember("p2", "Sam"), HouseholdMember("p3", "Jo"))
        val c = controller(
            list = { members },
            archive = { _, id ->
                archived += id
                members = members.filterNot { it.id == id }
            },
        )
        c.load("h1")
        var done = false
        c.archive("p3") { done = true }
        assertEquals(listOf("p3"), archived)
        assertEquals(listOf("Andrew", "Sam"), applied.map { it.name })
        assertTrue(done)
        assertFalse(c.busy)
    }

    @Test
    fun refusedArchiveShowsTheServerMessageAndStays() {
        val c = controller(archive = { _, _ -> throw refused("Jo is still named on a draft receipt") })
        c.load("h1")
        var done = false
        c.archive("p3") { done = true }
        assertEquals("Jo is still named on a draft receipt", c.message)
        assertFalse(done)
        assertFalse(c.busy)
    }
}