package com.monster0506.divvy.ui.state

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.ui.model.Appearance
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.appearance
import com.monster0506.divvy.ui.model.defaultAvatarColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DivvyStateMembersTest {
    private fun members(vararg pairs: Pair<String, String>) = pairs.map { (id, name) -> HouseholdMember(id, name) }

    /** Stands in for a decoded photo; only its identity matters here. */
    private class Photo : ImageBitmap {
        override val width = 1
        override val height = 1
        override val colorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = ImageBitmapConfig.Argb8888
        override fun readPixels(
            buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int,
        ) = Unit
        override fun prepareToDraw() = Unit
    }

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
    fun loadShowsTheSavedLookAndReplacesWhatWasShownHere() {
        val state = DivvyState()
        val chosen = AvatarColor.entries.first { it != defaultAvatarColor("b") }
        state.loadMembers(members("a" to "Ann", "b" to "Bo"))
        state.updatePerson(state.person("b")!!.copy(emoji = "x"))
        state.loadMembers(listOf(HouseholdMember("a", "Ann"), HouseholdMember("b", "Robert", shortName = "Rb", color = chosen.key)))

        val bo = state.person("b")!!
        assertEquals("Robert", bo.name)
        assertEquals(Appearance(shortName = "Rb", emoji = null, color = chosen), bo.appearance())
        val ann = state.person("a")!!
        assertEquals("An", ann.initials)
        assertEquals(defaultAvatarColor("a"), ann.color)
        assertEquals(Appearance(), ann.appearance())
    }

    @Test
    fun eachPhotoVersionIsDownloadedOnceAndKeptUntilANewerOneArrives() {
        val state = DivvyState()
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        assertEquals(mapOf("a" to "v1"), state.takePhotosToLoad())
        assertTrue(state.takePhotosToLoad().isEmpty())

        val first = Photo()
        state.showPhoto("a", "v1", first)
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        assertSame(first, state.person("a")!!.photo)
        assertTrue(state.takePhotosToLoad().isEmpty())

        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v2")))
        assertSame(first, state.person("a")!!.photo)
        assertEquals(mapOf("a" to "v2"), state.takePhotosToLoad())
    }

    @Test
    fun aDownloadOfAnOutdatedPhotoIsNotShown() {
        val state = DivvyState()
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        state.takePhotosToLoad()
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v2")))
        state.showPhoto("a", "v1", Photo())
        assertNull(state.person("a")!!.photo)
    }

    @Test
    fun aFailedDownloadIsTriedAgain() {
        val state = DivvyState()
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        state.takePhotosToLoad()
        state.showPhoto("a", "v1", null)
        assertEquals(mapOf("a" to "v1"), state.takePhotosToLoad())
    }

    @Test
    fun aRemovedPhotoIsNoLongerShown() {
        val state = DivvyState()
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        state.takePhotosToLoad()
        state.showPhoto("a", "v1", Photo())
        state.loadMembers(listOf(HouseholdMember("a", "Ann")))
        assertNull(state.person("a")!!.photo)
        assertTrue(state.takePhotosToLoad().isEmpty())
    }

    @Test
    fun aSavedPhotoIsNotDownloadedAgain() {
        val state = DivvyState()
        state.loadMembers(members("a" to "Ann"))
        val picked = Photo()
        state.previewPhoto("a", picked)
        state.photoSaved("a", "v1", picked)
        state.loadMembers(listOf(HouseholdMember("a", "Ann", photoUpdatedAt = "v1")))
        assertSame(picked, state.person("a")!!.photo)
        assertTrue(state.takePhotosToLoad().isEmpty())
    }

    @Test
    fun clearingTheShortNameGoesBackToInitialsFromTheName() {
        val state = DivvyState()
        state.loadMembers(listOf(HouseholdMember("a", "Mary Jane", shortName = "Mj")))
        state.applyAppearance("a", Appearance(shortName = null))
        assertEquals("MJ", state.person("a")!!.initials)
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
