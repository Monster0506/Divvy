package com.monster0506.divvy.settings

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.HouseholdMember
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.AppearanceRequest
import com.monster0506.divvy.ui.model.Appearance
import com.monster0506.divvy.ui.model.AvatarColor
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
    private val looks = mutableMapOf<String, Appearance>()
    private val restyled = mutableListOf<AppearanceRequest>()

    private fun controller(
        list: suspend (String) -> List<HouseholdMember> = {
            listOf(
                HouseholdMember("p1", "Andrew", shortName = "AA", emoji = "🍕"),
                HouseholdMember("p2", "Sam"),
                HouseholdMember("p3", "Jo"),
            )
        },
        rename: suspend (String, String, String) -> HouseholdMember = { _, id, name ->
            renamed += id to name
            HouseholdMember(id, name)
        },
        archive: suspend (String, String) -> Unit = { _, id -> archived += id },
        restyle: suspend (String, String, AppearanceRequest) -> HouseholdMember = { _, id, change ->
            restyled += change
            HouseholdMember(
                id, "Andrew",
                shortName = change.shortName?.ifEmpty { null } ?: "AA".takeIf { change.shortName == null },
                emoji = change.emoji?.ifEmpty { null } ?: "🍕".takeIf { change.emoji == null },
                color = change.color?.ifEmpty { null },
            )
        },
        photo: suspend (String, String, ByteArray?) -> HouseholdMember = { _, id, jpeg ->
            HouseholdMember(id, "Andrew", photoUpdatedAt = jpeg?.let { "2026-01-01T00:00:00Z" })
        },
    ) = MemberSyncController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        listMembers = list,
        renameMember = rename,
        archiveMember = archive,
        applyMembers = { applied = it },
        applyName = { id, name -> names[id] = name },
        saveAppearance = restyle,
        applyAppearance = { id, look -> looks[id] = look },
        savePhoto = photo,
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

    @Test
    fun restyleSendsOnlyWhatChangedAndAnEmptyStringToClear() {
        val c = controller()
        c.load("h1")
        c.restyle("p1", Appearance(shortName = " ", emoji = "🍕", color = AvatarColor.Teal))
        assertEquals(listOf(AppearanceRequest(shortName = "", emoji = null, color = "teal")), restyled)
        assertNull(c.lookMessage)
    }

    @Test
    fun unchangedLookIsNotSent() {
        val c = controller()
        c.load("h1")
        c.restyle("p1", Appearance(shortName = "AA", emoji = "🍕"))
        assertTrue(restyled.isEmpty())
    }

    @Test
    fun refusedRestylePutsTheSavedLookBack() {
        val c = controller(restyle = { _, _, _ -> throw refused("shortName must be 1 or 2 characters") })
        c.load("h1")
        c.restyle("p1", Appearance(shortName = "B", emoji = null))
        assertEquals(Appearance(shortName = "AA", emoji = "🍕"), looks["p1"])
        assertEquals("shortName must be 1 or 2 characters", c.lookMessage)
        assertNull(c.message)
    }

    @Test
    fun savedPhotoReportsTheNewVersion() {
        val c = controller()
        c.load("h1")
        var saved: HouseholdMember? = null
        c.setPhoto("p1", byteArrayOf(1), onSaved = { saved = it }, onRefused = { error("not refused") })
        assertEquals("2026-01-01T00:00:00Z", saved?.photoUpdatedAt)
    }

    @Test
    fun refusedPhotoShowsWhyAndAsksForTheSavedOneBack() {
        val c = controller(photo = { _, _, _ -> throw refused("photo must be JPEG or PNG") })
        c.load("h1")
        var refusedCalled = false
        c.setPhoto("p1", byteArrayOf(1), onSaved = { error("not saved") }, onRefused = { refusedCalled = true })
        assertTrue(refusedCalled)
        assertEquals("photo must be JPEG or PNG", c.lookMessage)
    }
}