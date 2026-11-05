package com.monster0506.divvy.shopping

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.OFFLINE_MESSAGE
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import com.monster0506.divvy.ui.model.ShoppingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ListPhase { Idle, Loading, Ready, Failed }

data class ShoppingListState(
    val phase: ListPhase = ListPhase.Idle,
    /** Items still to buy, oldest first, then bought ones. */
    val items: List<ShoppingItem> = emptyList(),
    /** What is typed in the add field. */
    val input: String = "",
    /** Things the household has bought before that match [input]. */
    val suggestions: List<String> = emptyList(),
    /** True while an item is being added. */
    val adding: Boolean = false,
    /** True while edits from the detail screen are being saved. */
    val saving: Boolean = false,
    /** Why the last call failed. */
    val message: String? = null,
    /** An item just swiped away, which [ShoppingListController.undoRemove] can bring back for a short while. */
    val removed: ShoppingItem? = null,
    /** True while a reload the person asked for, by pulling down, is running. */
    val refreshing: Boolean = false,
    /** Items the last reload found gone because someone else removed them: id to name. */
    val removedElsewhere: Map<String, String> = emptyMap(),
) {
    val open get() = items.filter { !it.done }
    val purchased get() = items.filter { it.done }
}

/**
 * The household's shared grocery list. Changes show at once and are sent to the server; one that the
 * server refuses is put back with a message saying why. [sync] picks up other people's changes; a reload
 * that overlaps one of this device's own changes is not shown, so it cannot undo that change on screen.
 */
class ShoppingListController(
    private val api: ShoppingListApi,
    private val scope: CoroutineScope,
    /** The person this device acts as, recorded as who added an item. */
    private val currentPerson: (householdId: String) -> String? = { null },
    private val autocompleteDelayMillis: Long = 250,
    private val undoWindowMillis: Long = 8_000,
) {
    private val mutableState = MutableStateFlow(ShoppingListState())
    val state: StateFlow<ShoppingListState> = mutableState

    private var householdId: String? = null
    private var loadJob: Job? = null
    private var autocompleteJob: Job? = null
    private var undoJob: Job? = null

    /** This device's changes still being saved. */
    private var writes = 0
    /** Goes up whenever one of this device's changes starts or finishes saving. */
    private var changes = 0
    /** A reload the person asked for, held back until this device's changes have been saved. */
    private var reloadWhenSaved: Reload? = null

    private enum class Reload { Open, Pull, Background }

    fun item(id: String): ShoppingItem? = mutableState.value.items.find { it.id == id }

    /** Shows [householdId]'s list, loading it afresh. */
    fun open(householdId: String) {
        if (this.householdId != householdId) mutableState.value = ShoppingListState()
        this.householdId = householdId
        load(Reload.Open)
    }

    /** Loads the list again because the person asked: pulling down, or Try again after a failed load. */
    fun refresh() = load(Reload.Pull)

    /**
     * Loads the list again to show other people's changes. It says nothing when it fails, keeps what is shown,
     * and does not run while another load or one of this device's changes is still going.
     */
    fun sync() = load(Reload.Background)

    private fun load(kind: Reload) {
        val id = householdId ?: return
        if (kind == Reload.Background && (loadJob?.isActive == true || writes > 0)) return
        loadJob?.cancel()
        if (kind != Reload.Background) mutableState.update {
            if (it.phase == ListPhase.Ready) it.copy(message = null, refreshing = kind == Reload.Pull)
            else it.copy(phase = ListPhase.Loading, message = null)
        }
        val startedAt = changes
        loadJob = scope.launch {
            val result = safely { api.items(id) }
            if (changes != startedAt || writes > 0) {
                // The answer may predate a change this device was saving meanwhile; showing it would undo that change.
                if (kind != Reload.Background) {
                    if (writes == 0) load(kind) else reloadWhenSaved = kind
                }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> mutableState.update {
                    val items = result.value.map { dto -> dto.toItem() }.ordered()
                    val ids = items.mapTo(HashSet()) { item -> item.id }
                    val gone = if (it.phase == ListPhase.Ready) it.items.filter { old -> old.id !in ids } else emptyList()
                    it.copy(
                        phase = ListPhase.Ready,
                        items = items,
                        refreshing = false,
                        removedElsewhere = gone.associate { old -> old.id to old.name },
                    )
                }
                is ApiResult.Failure -> mutableState.update {
                    if (kind == Reload.Background) it
                    else it.copy(
                        phase = if (it.phase == ListPhase.Ready) ListPhase.Ready else ListPhase.Failed,
                        refreshing = false,
                        message = describe(result.failure, "load the list"),
                    )
                }
            }
        }
    }

    /** Leaving an item's screen because someone else removed it says so on the list. */
    fun noteRemovedElsewhere(itemId: String) {
        val name = mutableState.value.removedElsewhere[itemId] ?: return
        mutableState.update { it.copy(message = "$name was removed from the list by someone else.") }
    }

    /** Updates the add field and, after a short pause in typing, what it suggests. */
    fun type(text: String) {
        mutableState.update { it.copy(input = text) }
        autocompleteJob?.cancel()
        val id = householdId
        val query = text.trim()
        if (id == null || query.length < 2) {
            mutableState.update { it.copy(suggestions = emptyList()) }
            return
        }
        autocompleteJob = scope.launch {
            delay(autocompleteDelayMillis)
            val result = safely { api.autocomplete(id, query) }
            // A failed lookup only means no suggestions; it is not worth a message.
            val found = (result as? ApiResult.Success)?.value.orEmpty()
            mutableState.update { s ->
                val onList = s.open.map { it.name.lowercase() }.toSet()
                s.copy(suggestions = found.filter { it.lowercase() !in onList && !it.equals(query, ignoreCase = true) })
            }
        }
    }

    /** Puts [text] on the list, by default what is typed in the add field. */
    fun add(text: String = mutableState.value.input) {
        val id = householdId ?: return
        val name = text.trim()
        if (name.isEmpty() || mutableState.value.adding) return
        autocompleteJob?.cancel()
        mutableState.update { it.copy(adding = true, message = null) }
        write {
            val request = CreateListItemRequest(name, addedByPersonId = currentPerson(id))
            when (val result = safely { api.add(id, request) }) {
                is ApiResult.Success -> mutableState.update { s ->
                    // Only clear the field if it still holds what was added; a suggestion tap leaves typing alone.
                    val typed = s.input.trim().equals(name, ignoreCase = true)
                    s.copy(
                        adding = false,
                        items = (s.items + result.value.toItem()).ordered(),
                        input = if (typed) "" else s.input,
                        suggestions = if (typed) emptyList() else s.suggestions.filterNot { it.equals(name, ignoreCase = true) },
                    )
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(adding = false, message = describe(result.failure, "add $name"))
                }
            }
        }
    }

    /** Checks an item off, or back on. */
    fun toggle(itemId: String) {
        val id = householdId ?: return
        val before = item(itemId) ?: return
        setItem(before.copy(done = !before.done))
        write {
            when (val result = safely { api.update(id, itemId, UpdateListItemRequest(purchased = !before.done)) }) {
                is ApiResult.Success -> setItem(result.value.toItem())
                is ApiResult.Failure -> {
                    item(itemId)?.let { setItem(it.copy(done = before.done)) }
                    mutableState.update { it.copy(message = describe(result.failure, "update ${before.name}")) }
                }
            }
        }
    }

    /**
     * Saves the detail screen's edits and calls [onSaved] once the server has them. A blank
     * [quantity] or [notes] clears it; [name] must not be blank.
     */
    fun save(itemId: String, name: String, quantity: String, notes: String, onSaved: () -> Unit = {}) {
        val id = householdId ?: return
        val before = item(itemId) ?: return
        if (mutableState.value.saving) return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            mutableState.update { it.copy(message = "Give the item a name.") }
            return
        }
        val change = UpdateListItemRequest(
            text = trimmedName.takeIf { it != before.name },
            quantity = quantity.trim().takeIf { it != before.quantity.orEmpty() },
            notes = notes.trim().takeIf { it != before.notes.orEmpty() },
        )
        if (change == UpdateListItemRequest()) {
            onSaved()
            return
        }
        mutableState.update { it.copy(saving = true, message = null) }
        write {
            when (val result = safely { api.update(id, itemId, change) }) {
                is ApiResult.Success -> {
                    setItem(result.value.toItem())
                    mutableState.update { it.copy(saving = false) }
                    onSaved()
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(saving = false, message = describe(result.failure, "save ${before.name}"))
                }
            }
        }
    }

    /**
     * Takes an item off the list and calls [onRemoved] once the server has removed it. With
     * [offerUndo] it can be brought back for a short while.
     */
    fun remove(itemId: String, offerUndo: Boolean = false, onRemoved: () -> Unit = {}) {
        val id = householdId ?: return
        val items = mutableState.value.items
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return
        val item = items[index]
        mutableState.update { s -> s.copy(items = s.items.filterNot { it.id == itemId }, message = null) }
        write {
            when (val result = safely { api.remove(id, itemId) }) {
                is ApiResult.Success -> {
                    onRemoved()
                    if (offerUndo) {
                        mutableState.update { it.copy(removed = item) }
                        startUndoWindow()
                    }
                }
                is ApiResult.Failure -> mutableState.update { s ->
                    s.copy(items = (s.items + item).ordered(), message = describe(result.failure, "remove ${item.name}"))
                }
            }
        }
    }

    /** Puts the item just swiped away back on the list. It comes back as a new entry at the end. */
    fun undoRemove() {
        val id = householdId ?: return
        val item = mutableState.value.removed ?: return
        undoJob?.cancel()
        mutableState.update { it.copy(removed = null, message = null) }
        write {
            val request = CreateListItemRequest(item.name, item.quantity, item.notes, item.addedById)
            var restored = when (val result = safely { api.add(id, request) }) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> {
                    mutableState.update { it.copy(message = describe(result.failure, "put ${item.name} back")) }
                    return@write
                }
            }
            if (item.done) {
                (safely { api.update(id, restored.id, UpdateListItemRequest(purchased = true)) } as? ApiResult.Success)
                    ?.let { restored = it.value }
            }
            mutableState.update { s -> s.copy(items = (s.items + restored.toItem()).ordered()) }
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private fun startUndoWindow() {
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(undoWindowMillis)
            mutableState.update { it.copy(removed = null) }
        }
    }

    /** Saves one of this device's changes, holding back reloads that overlap it. */
    private fun write(change: suspend () -> Unit) {
        writes++
        changes++
        scope.launch {
            try {
                change()
            } finally {
                writes--
                changes++
                if (writes == 0) reloadWhenSaved?.let { kind ->
                    reloadWhenSaved = null
                    load(kind)
                }
            }
        }
    }

    private fun setItem(item: ShoppingItem) =
        mutableState.update { s -> s.copy(items = s.items.map { if (it.id == item.id) item else it }.ordered()) }

    private suspend fun <T> safely(call: suspend () -> ApiResult<T>): ApiResult<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(ApiFailure.ServerError(null))
        }

    private fun describe(failure: ApiFailure, action: String): String = when (failure) {
        ApiFailure.Offline -> "$OFFLINE_MESSAGE Could not $action."
        ApiFailure.Unauthorised -> "The server rejected the API key. Check Account > Server."
        is ApiFailure.Validation ->
            if (failure.error.code == "not_found") "Could not $action: it is no longer on the list." else failure.error.message
        is ApiFailure.ServerError -> "Could not $action. The server had a problem; try again in a moment."
    }
}

private fun ListItemDto.toItem() = ShoppingItem(
    id = id,
    name = text,
    done = purchased,
    quantity = quantity,
    notes = notes,
    addedBy = addedByName,
    addedById = addedByPersonId,
    addedAt = createdAt,
)

/** Items to buy first, then bought ones, each oldest first, the way the server lists them. */
internal fun List<ShoppingItem>.ordered(): List<ShoppingItem> =
    sortedWith(compareBy<ShoppingItem> { it.done }.thenBy { sortableInstant(it.addedAt) })

/**
 * The server writes instants as UTC with a variable number of fraction digits, which do not sort as
 * text ("…00Z" comes after "…00.5Z"). Padding the fraction fixes that. Unknown times sort last.
 */
private fun sortableInstant(iso: String?): String {
    if (iso == null) return "￿"
    val parts = iso.removeSuffix("Z").split('.', limit = 2)
    return parts[0] + "." + parts.getOrElse(1) { "" }.padEnd(9, '0')
}
