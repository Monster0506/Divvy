package com.monster0506.divvy.settings

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.dto.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses an unconfined scope and no debounce, with fakes that never suspend, so everything runs inline. */
class HouseholdNameControllerTest {
    private var local = ""
    private val renamed = mutableListOf<String>()

    private fun controller(
        fetch: suspend (String) -> String = { "Apartment 4B" },
        rename: suspend (String, String) -> String = { _, name -> renamed += name; name },
    ) = HouseholdNameController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        fetch = fetch,
        rename = rename,
        setLocal = { local = it },
        debounceMillis = 0,
    )

    @Test
    fun openLoadsTheServerName() {
        val c = controller(fetch = { "Home" })
        c.open("h1")
        assertEquals("Home", local)
        assertNull(c.message)
    }

    @Test
    fun editSavesTheTrimmedName() {
        val c = controller()
        c.open("h1")
        c.edit("  Casa  ")
        assertEquals(listOf("Casa"), renamed)
        assertNull(c.message)
    }

    @Test
    fun refusedRenameRevertsWithTheServerMessage() {
        val c = controller(rename = { _, _ -> throw ApiException(ApiFailure.Validation(ApiError("taken", "Name already used"))) })
        c.open("h1")
        c.edit("Casa")
        assertEquals("Apartment 4B", local)
        assertEquals("Name already used", c.message)
    }

    @Test
    fun offlineRenameRevertsWithAMessage() {
        val c = controller(rename = { _, _ -> throw ApiException(ApiFailure.Offline) })
        c.open("h1")
        c.edit("Casa")
        assertEquals("Apartment 4B", local)
        assertNotNull(c.message)
    }

    @Test
    fun blankNameIsNotSentAndIsReverted() {
        val c = controller()
        c.open("h1")
        c.edit("   ")
        assertTrue(renamed.isEmpty())
        assertEquals("Apartment 4B", local)
        assertNotNull(c.message)
    }

    @Test
    fun unchangedNameIsNotSent() {
        val c = controller()
        c.open("h1")
        c.edit("Apartment 4B ")
        assertTrue(renamed.isEmpty())
    }

    @Test
    fun failedLoadKeepsTheCurrentNameAndShowsAMessage() {
        local = "Apartment 4B"
        val c = controller(fetch = { throw ApiException(ApiFailure.Offline) })
        c.open("h1")
        assertEquals("Apartment 4B", local)
        assertNotNull(c.message)
    }
}