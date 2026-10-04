package com.monster0506.divvy.shopping

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.LIST_ITEM_OPEN
import com.monster0506.divvy.data.dto.LIST_ITEM_PURCHASED
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import com.monster0506.divvy.data.repository.ShoppingListRepository
import com.monster0506.divvy.ui.model.ShoppingItem

/** The server calls the grocery list needs, so it can be tested without a network. */
interface ShoppingListApi {
    suspend fun items(householdId: String): ApiResult<List<ListItemDto>>

    suspend fun add(householdId: String, request: CreateListItemRequest): ApiResult<ListItemDto>

    suspend fun update(householdId: String, itemId: String, change: UpdateListItemRequest): ApiResult<ListItemDto>

    suspend fun remove(householdId: String, itemId: String): ApiResult<Unit>

    /** Things the household has bought before whose names contain [query]. */
    suspend fun autocomplete(householdId: String, query: String): ApiResult<List<String>>
}

class RepositoryShoppingListApi(private val repository: ShoppingListRepository) : ShoppingListApi {
    override suspend fun items(householdId: String) = repository.items(householdId)

    override suspend fun add(householdId: String, request: CreateListItemRequest) = repository.add(householdId, request)

    override suspend fun update(householdId: String, itemId: String, change: UpdateListItemRequest) =
        repository.update(householdId, itemId, change)

    override suspend fun remove(householdId: String, itemId: String) = repository.remove(householdId, itemId)

    override suspend fun autocomplete(householdId: String, query: String) = repository.autocomplete(householdId, query)
}

/**
 * Keeps the list on the device for a household that is not on a server yet, so the screen works the
 * same way before setup. Autocomplete draws on [known] names.
 */
class LocalShoppingListApi(
    seed: List<ShoppingItem>,
    private val known: List<String> = emptyList(),
    private val nameOf: (personId: String) -> String? = { null },
) : ShoppingListApi {
    private var items = seed.map {
        ListItemDto(
            id = it.id,
            householdId = "",
            text = it.name,
            quantity = it.quantity,
            notes = it.notes,
            addedByName = it.addedBy,
            status = if (it.done) LIST_ITEM_PURCHASED else LIST_ITEM_OPEN,
            createdAt = it.addedAt,
        )
    }
    private var nextId = 0

    override suspend fun items(householdId: String) = ApiResult.Success(items.sortedBy { it.purchased })

    override suspend fun add(householdId: String, request: CreateListItemRequest): ApiResult<ListItemDto> {
        val item = ListItemDto(
            id = "local-${nextId++}",
            householdId = householdId,
            text = request.text.trim(),
            quantity = request.quantity?.trim()?.ifEmpty { null },
            notes = request.notes?.trim()?.ifEmpty { null },
            addedByPersonId = request.addedByPersonId,
            addedByName = request.addedByPersonId?.let(nameOf),
        )
        items = items + item
        return ApiResult.Success(item)
    }

    override suspend fun update(householdId: String, itemId: String, change: UpdateListItemRequest): ApiResult<ListItemDto> {
        val old = items.find { it.id == itemId } ?: return notFound()
        val new = old.copy(
            text = change.text?.trim() ?: old.text,
            quantity = if (change.quantity != null) change.quantity.trim().ifEmpty { null } else old.quantity,
            notes = if (change.notes != null) change.notes.trim().ifEmpty { null } else old.notes,
            status = when (change.purchased) {
                true -> LIST_ITEM_PURCHASED
                false -> LIST_ITEM_OPEN
                null -> old.status
            },
        )
        items = items.map { if (it.id == itemId) new else it }
        return ApiResult.Success(new)
    }

    override suspend fun remove(householdId: String, itemId: String): ApiResult<Unit> {
        if (items.none { it.id == itemId }) return notFound()
        items = items.filterNot { it.id == itemId }
        return ApiResult.Success(Unit)
    }

    override suspend fun autocomplete(householdId: String, query: String): ApiResult<List<String>> {
        val q = query.trim().lowercase()
        return ApiResult.Success(
            known.filter { q in it.lowercase() }.sortedByDescending { it.lowercase().startsWith(q) },
        )
    }

    private fun notFound() = ApiResult.Failure(ApiFailure.Validation(ApiError("not_found", "list item not found")))
}
