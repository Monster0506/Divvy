package com.monster0506.divvy.ui.state

import com.monster0506.divvy.data.api.HouseholdMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DivvyStateMembersTest {
    private fun members(vararg pairs: Pair<String, String>) = pairs.map { (id, name) -> HouseholdMember(id, name) }

    @Test
    fun nothingIsShownBeforeTheServerAnswers() {
        val state = DivvyState()
        assertTrue(state.people.isEmpty())
        assertNull(state.me)
        assertEquals("", state.householdName)
        assertNull(state.settings)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun loadShowsTheMembersWithoutGuessingWhoThisDeviceIs() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        assertEquals(listOf("Ann", "Bo"), state.people.map { it.name })
        assertNull(state.me)

        state.setMe("b")
        assertEquals("Bo", state.me!!.name)
    }

    @Test
    fun aMemberWhoIsNoLongerListedIsNoLongerMe() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        state.setMe("b")
        state.loadMembers(members("a" to "Ann", "c" to "Cy"))
        assertNull(state.meId)
    }

    @Test
    fun loadKeepsLocalEmojiByIdAndTakesTheServerName() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        state.updatePerson(state.person("b")!!.copy(emoji = "x"))
        state.loadMembers(members("a" to "Ann", "b" to "Robert"))
        assertEquals("Robert", state.person("b")!!.name)
        assertEquals("x", state.person("b")!!.emoji)
    }

    @Test
    fun loadIgnoresAnEmptyList() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        val before = state.people
        state.loadMembers(emptyList())
        assertEquals(before, state.people)
    }

    @Test
    fun renameKeepsInitialsInStepUntilCustomised() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        state.renamePerson("a", "Maria Jose")
        assertEquals("Maria Jose", state.person("a")!!.name)
        assertEquals("MJ", state.person("a")!!.initials)
    }
}
