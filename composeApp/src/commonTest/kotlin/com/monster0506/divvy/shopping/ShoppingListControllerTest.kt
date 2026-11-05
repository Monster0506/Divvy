package com.monster0506.divvy.shopping

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.LIST_ITEM_OPEN
import com.monster0506.divvy.data.dto.LIST_ITEM_PURCHASED
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory server that can be told to fail its next call. */
private class FakeListServer : ShoppingListApi {
    val items = mutableListOf<ListItemDto>()
    val added = mutableListOf<CreateListItemRequest>()
    val updates = mutableListOf<Pair<String, UpdateListItemRequest>>()
    val queries = mutableListOf<String>()
    var known = listOf<String>()
    var failNext: ApiFailure? = null
    /** While set, a load answers with the list as it was when asked, once this completes. */
    var holdItems: CompletableDeferred<Unit>? = null
    /** While set, an update waits for this before it changes anything. */
    var holdUpdates: CompletableDeferred<Unit>? = null
    private var clock = 0

    fun seed(text: String, purchased: Boolean = false, quantity: String? = null, notes: String? = null) =
        newItem(text, quantity, notes, null).copy(status = if (purchased) LIST_ITEM_PURCHASED else LIST_ITEM_OPEN).also { items += it }

    private fun newItem(text: String, quantity: String?, notes: String?, by: String?) = ListItemDto(
        id = "i${++clock}",
        householdId = "h1",
        text = text,
        quantity = quantity,
        notes = notes,
        addedByPersonId = by,
        addedByName = by?.let { "Name of $it" },
        // Real instants have a variable number of fraction digits; mix them to check ordering.
        createdAt = if (clock % 2 == 0) "2026-10-04T10:00:0${clock}Z" else "2026-10-04T10:00:0${clock}.5Z",
    )

    private fun <T> answer(value: () -> T): ApiResult<T> {
        val failure = failNext ?: return ApiResult.Success(value())
        failNext = null
        return ApiResult.Failure(failure)
    }

    override suspend fun items(householdId: String): ApiResult<List<ListItemDto>> {
        val asked = items.sortedBy { it.purchased }.toList()
        holdItems?.await()
        return answer { asked }
    }

    override suspend fun add(householdId: String, request: CreateListItemRequest) = answer {
        added += request
        newItem(request.text, request.quantity, request.notes, request.addedByPersonId).also { items += it }
    }

    override suspend fun update(householdId: String, itemId: String, change: UpdateListItemRequest): ApiResult<ListItemDto> {
        holdUpdates?.await()
        return answer {
            updates += itemId to change
            val i = items.indexOfFirst { it.id == itemId }
            val old = items[i]
            old.copy(
                text = change.text ?: old.text,
                quantity = if (change.quantity != null) change.quantity.ifEmpty { null } else old.quantity,
                notes = if (change.notes != null) change.notes.ifEmpty { null } else old.notes,
                status = change.purchased?.let { if (it) LIST_ITEM_PURCHASED else LIST_ITEM_OPEN } ?: old.status,
            ).also { items[i] = it }
        }
    }

    override suspend fun remove(householdId: String, itemId: String) = answer { items.removeAll { it.id == itemId }; Unit }

    override suspend fun autocomplete(householdId: String, query: String) = answer {
        queries += query
        known.filter { query.lowercase() in it.lowercase() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingListControllerTest {
    private val server = FakeListServer()

    private fun TestScope.opened(me: String? = "p1"): ShoppingListController =
        ShoppingListController(server, this, currentPerson = { me }).also {
            it.open("h1")
            advanceUntilIdle()
        }

    private val ShoppingListController.names get() = state.value.items.map { it.name }

    @Test
    fun opening_shows_items_to_buy_before_bought_ones() = runTest {
        server.seed("Eggs")
        server.seed("Olive oil", purchased = true)
        server.seed("Milk")

        val c = opened()

        assertEquals(ListPhase.Ready, c.state.value.phase)
        assertEquals(listOf("Eggs", "Milk"), c.state.value.open.map { it.name })
        assertEquals(listOf("Olive oil"), c.state.value.purchased.map { it.name })
    }

    @Test
    fun a_failed_load_says_why_and_can_be_retried() = runTest {
        server.failNext = ApiFailure.Offline
        val c = opened()
        assertEquals(ListPhase.Failed, c.state.value.phase)
        assertTrue(c.state.value.message!!.startsWith("Could not reach the server"))

        server.seed("Eggs")
        c.refresh()
        advanceUntilIdle()

        assertEquals(ListPhase.Ready, c.state.value.phase)
        assertEquals(listOf("Eggs"), c.names)
    }

    @Test
    fun adding_puts_the_item_at_the_end_with_who_added_it_and_clears_the_field() = runTest {
        server.seed("Eggs")
        val c = opened(me = "p7")

        c.type("  Bread ")
        c.add()
        assertTrue(c.state.value.adding)
        advanceUntilIdle()

        assertEquals(listOf("Eggs", "Bread"), c.names)
        assertEquals(CreateListItemRequest("Bread", addedByPersonId = "p7"), server.added.single())
        assertEquals("Name of p7", c.state.value.items.last().addedBy)
        assertEquals("", c.state.value.input)
        assertFalse(c.state.value.adding)
    }

    @Test
    fun a_blank_item_is_not_added() = runTest {
        val c = opened()
        c.type("   ")
        c.add()
        advanceUntilIdle()
        assertTrue(server.added.isEmpty())
    }

    @Test
    fun a_failed_add_keeps_what_was_typed() = runTest {
        val c = opened()
        c.type("Bread")
        server.failNext = ApiFailure.ServerError(500)
        c.add()
        advanceUntilIdle()

        assertEquals("Bread", c.state.value.input)
        assertTrue(c.names.isEmpty())
        assertTrue(c.state.value.message!!.startsWith("Could not add Bread"))
    }

    @Test
    fun typing_suggests_past_purchases_after_a_pause_without_ones_already_on_the_list() = runTest {
        server.seed("Large eggs")
        server.known = listOf("Large eggs", "Eggplant", "Free range eggs")
        val c = opened()

        c.type("e")
        advanceUntilIdle()
        assertTrue(server.queries.isEmpty(), "one letter is too short to look up")

        c.type("eg")
        c.type("egg")
        advanceTimeBy(100)
        assertTrue(server.queries.isEmpty(), "still typing")
        advanceUntilIdle()

        assertEquals(listOf("egg"), server.queries)
        assertEquals(listOf("Eggplant", "Free range eggs"), c.state.value.suggestions)
    }

    @Test
    fun tapping_a_suggestion_adds_it_and_drops_it_from_the_suggestions() = runTest {
        server.known = listOf("Eggplant", "Free range eggs")
        val c = opened()
        c.type("egg")
        advanceUntilIdle()

        c.add("Eggplant")
        advanceUntilIdle()

        assertEquals(listOf("Eggplant"), c.names)
        assertEquals(listOf("Free range eggs"), c.state.value.suggestions)
        assertEquals("egg", c.state.value.input)
    }

    @Test
    fun checking_off_moves_the_item_down_at_once_and_tells_the_server() = runTest {
        server.seed("Eggs")
        server.seed("Milk")
        val c = opened()
        val eggs = c.state.value.items.first().id

        c.toggle(eggs)
        assertEquals(listOf("Milk", "Eggs"), c.names, "shown before the server answers")
        advanceUntilIdle()

        assertEquals(UpdateListItemRequest(purchased = true), server.updates.single().second)
        assertTrue(c.item(eggs)!!.done)
    }

    @Test
    fun a_check_off_the_server_refuses_is_undone() = runTest {
        server.seed("Eggs")
        val c = opened()
        val eggs = c.state.value.items.single().id

        server.failNext = ApiFailure.Offline
        c.toggle(eggs)
        advanceUntilIdle()

        assertFalse(c.item(eggs)!!.done)
        assertNotNull(c.state.value.message)
    }

    @Test
    fun saving_sends_only_what_changed_and_an_emptied_field_clears_it() = runTest {
        server.seed("Eggs", quantity = "6", notes = "Brown")
        val c = opened()
        val eggs = c.state.value.items.single().id
        var saved = false

        c.save(eggs, " Large eggs ", quantity = "", notes = "Brown") { saved = true }
        assertTrue(c.state.value.saving)
        advanceUntilIdle()

        assertEquals(UpdateListItemRequest(text = "Large eggs", quantity = ""), server.updates.single().second)
        val item = c.item(eggs)!!
        assertEquals("Large eggs", item.name)
        assertNull(item.quantity)
        assertEquals("Brown", item.notes)
        assertTrue(saved)
        assertFalse(c.state.value.saving)
    }

    @Test
    fun saving_without_changes_goes_straight_back() = runTest {
        server.seed("Eggs")
        val c = opened()
        var saved = false

        c.save(c.state.value.items.single().id, "Eggs", "", "") { saved = true }

        assertTrue(saved)
        assertTrue(server.updates.isEmpty())
    }

    @Test
    fun an_item_cannot_be_saved_without_a_name() = runTest {
        server.seed("Eggs")
        val c = opened()
        var saved = false

        c.save(c.state.value.items.single().id, "  ", "", "") { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertTrue(server.updates.isEmpty())
        assertEquals("Give the item a name.", c.state.value.message)
    }

    @Test
    fun a_failed_save_stays_on_the_item_with_a_message() = runTest {
        server.seed("Eggs")
        val c = opened()
        var saved = false

        server.failNext = ApiFailure.ServerError(500)
        c.save(c.state.value.items.single().id, "Large eggs", "", "") { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertEquals("Eggs", c.names.single())
        assertNotNull(c.state.value.message)
    }

    @Test
    fun removing_from_the_detail_screen_takes_it_off_with_no_undo() = runTest {
        server.seed("Eggs")
        server.seed("Milk")
        val c = opened()
        var removed = false

        c.remove(c.state.value.items.first().id) { removed = true }
        assertEquals(listOf("Milk"), c.names, "gone before the server answers")
        advanceUntilIdle()

        assertTrue(removed)
        assertEquals(listOf("Milk"), server.items.map { it.text })
        assertNull(c.state.value.removed)
    }

    @Test
    fun a_removal_the_server_refuses_puts_the_item_back() = runTest {
        server.seed("Eggs")
        server.seed("Milk")
        val c = opened()

        server.failNext = ApiFailure.Validation(ApiError("internal", "nope"))
        c.remove(c.state.value.items.first().id)
        advanceUntilIdle()

        assertEquals(listOf("Eggs", "Milk"), c.names)
        assertEquals("nope", c.state.value.message)
    }

    @Test
    fun a_swiped_away_item_can_be_brought_back_with_its_details() = runTest {
        server.seed("Olive oil", purchased = true, quantity = "1 L", notes = "Extra virgin")
        val c = opened()

        c.remove(c.state.value.items.single().id, offerUndo = true)
        runCurrent() // not advanceUntilIdle, which would also run out the undo window
        assertEquals("Olive oil", c.state.value.removed?.name)
        assertTrue(c.names.isEmpty())

        c.undoRemove()
        advanceUntilIdle()

        val back = c.state.value.items.single()
        assertEquals("Olive oil", back.name)
        assertEquals("1 L", back.quantity)
        assertEquals("Extra virgin", back.notes)
        assertTrue(back.done)
        assertNull(c.state.value.removed)
        assertEquals(1, server.items.size)
    }

    @Test
    fun the_undo_offer_goes_away_after_a_while() = runTest {
        server.seed("Eggs")
        val c = ShoppingListController(server, this, undoWindowMillis = 1_000)
        c.open("h1")
        advanceUntilIdle()

        c.remove(c.state.value.items.single().id, offerUndo = true)
        runCurrent()
        assertNotNull(c.state.value.removed)
        advanceTimeBy(1_001)

        assertNull(c.state.value.removed)
    }

    @Test
    fun opening_another_household_drops_the_old_list() = runTest {
        server.seed("Eggs")
        val c = opened()

        server.failNext = ApiFailure.Offline
        c.open("h2")
        advanceUntilIdle()

        assertEquals(ListPhase.Failed, c.state.value.phase)
        assertTrue(c.names.isEmpty())
    }

    // --- other people's changes -------------------------------------------------------------------

    @Test
    fun syncing_shows_what_others_added_and_checked_off() = runTest {
        val eggs = server.seed("Eggs")
        val c = opened()
        server.seed("Bread")
        server.items[0] = eggs.copy(status = LIST_ITEM_PURCHASED)

        c.sync()
        advanceUntilIdle()

        assertEquals(listOf("Bread"), c.state.value.open.map { it.name })
        assertEquals(listOf("Eggs"), c.state.value.purchased.map { it.name })
        assertFalse(c.state.value.refreshing)
    }

    @Test
    fun a_sync_that_fails_keeps_the_list_and_says_nothing() = runTest {
        server.seed("Eggs")
        val c = opened()

        server.failNext = ApiFailure.Offline
        c.sync()
        advanceUntilIdle()

        assertEquals(ListPhase.Ready, c.state.value.phase)
        assertEquals(listOf("Eggs"), c.names)
        assertNull(c.state.value.message)
    }

    @Test
    fun pulling_down_shows_the_indicator_and_says_why_it_failed() = runTest {
        server.seed("Eggs")
        val c = opened()
        server.holdItems = CompletableDeferred()

        c.refresh()
        runCurrent()
        assertTrue(c.state.value.refreshing)
        server.failNext = ApiFailure.Offline
        server.holdItems!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(c.state.value.refreshing)
        assertEquals(listOf("Eggs"), c.names)
        assertTrue(c.state.value.message!!.startsWith("Could not reach the server"))
    }

    @Test
    fun a_sync_asked_before_a_check_off_was_saved_does_not_undo_it() = runTest {
        server.seed("Eggs")
        val c = opened()
        val eggs = c.state.value.items.single().id
        server.holdItems = CompletableDeferred()

        c.sync()
        runCurrent()
        c.toggle(eggs)
        advanceUntilIdle()
        server.holdItems!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(c.state.value.items.single().done)
    }

    @Test
    fun pulling_down_while_a_check_off_is_saving_waits_for_it() = runTest {
        server.seed("Eggs")
        val c = opened()
        val eggs = c.state.value.items.single().id
        server.holdUpdates = CompletableDeferred()

        c.toggle(eggs)
        runCurrent()
        c.refresh()
        runCurrent()
        assertTrue(c.state.value.items.single().done)
        assertTrue(c.state.value.refreshing)

        server.holdUpdates!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(c.state.value.items.single().done)
        assertFalse(c.state.value.refreshing)
    }

    @Test
    fun only_items_removed_on_another_phone_are_reported_as_removed_elsewhere() = runTest {
        server.seed("Eggs")
        server.seed("Milk")
        val c = opened()
        val (eggs, milk) = c.state.value.items

        c.remove(eggs.id)
        advanceUntilIdle()
        server.items.removeAll { it.id == milk.id }
        c.sync()
        advanceUntilIdle()

        assertEquals(mapOf(milk.id to "Milk"), c.state.value.removedElsewhere)
        c.noteRemovedElsewhere(eggs.id)
        assertNull(c.state.value.message)
        c.noteRemovedElsewhere(milk.id)
        assertEquals("Milk was removed from the list by someone else.", c.state.value.message)
    }
}

class LocalShoppingListApiTest {
    @Test
    fun emptying_a_field_clears_it() = runTest {
        val api = LocalShoppingListApi(emptyList())
        val item = (api.add("local", CreateListItemRequest("Eggs", quantity = "6")) as ApiResult.Success).value

        val updated = (api.update("local", item.id, UpdateListItemRequest(quantity = "")) as ApiResult.Success).value

        assertNull(updated.quantity)
        assertEquals("Eggs", updated.text)
    }
}
