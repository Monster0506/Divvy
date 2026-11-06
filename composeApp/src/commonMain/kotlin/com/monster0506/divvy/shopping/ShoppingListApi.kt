package com.monster0506.divvy.shopping

import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import com.monster0506.divvy.data.repository.ShoppingListRepository

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
