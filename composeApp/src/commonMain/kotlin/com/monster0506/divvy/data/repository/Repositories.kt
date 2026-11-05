package com.monster0506.divvy.data.repository

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.api.DivvyApi
import com.monster0506.divvy.data.dto.AcceptedPredictionsDto
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.ConfirmedReceiptDto
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.CreateSettlementRequest
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import com.monster0506.divvy.data.dto.SettlementDto
import com.monster0506.divvy.data.dto.EditedReceiptDto
import com.monster0506.divvy.data.dto.ItemEditRequest
import com.monster0506.divvy.data.dto.MlSettingsDto
import com.monster0506.divvy.data.dto.ReceiptPageDto
import com.monster0506.divvy.data.dto.UpdateReceiptRequest
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.HouseholdDto
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptUploadDto
import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> apiCall(block: () -> T): ApiResult<T> =
    try {
        ApiResult.Success(block())
    } catch (e: ApiException) {
        ApiResult.Failure(e.failure)
    } catch (e: CancellationException) {
        throw e
    }

class HouseholdRepository(private val api: DivvyApi) {
    suspend fun create(name: String): ApiResult<HouseholdDto> = apiCall { api.createHousehold(name) }

    suspend fun get(id: String): ApiResult<HouseholdDto> = apiCall { api.getHousehold(id) }

    suspend fun rename(id: String, name: String): ApiResult<HouseholdDto> = apiCall { api.renameHousehold(id, name) }

    suspend fun people(householdId: String, includeArchived: Boolean = false): ApiResult<List<PersonDto>> =
        apiCall { api.listPeople(householdId, includeArchived) }

    suspend fun addPerson(householdId: String, displayName: String): ApiResult<PersonDto> =
        apiCall { api.addPerson(householdId, displayName) }

    suspend fun renamePerson(householdId: String, personId: String, displayName: String): ApiResult<PersonDto> =
        apiCall { api.renamePerson(householdId, personId, displayName) }

    suspend fun archivePerson(householdId: String, personId: String): ApiResult<PersonDto> =
        apiCall { api.archivePerson(householdId, personId) }

    suspend fun setVenmoUsername(householdId: String, personId: String, username: String): ApiResult<PersonDto> =
        apiCall { api.setVenmoUsername(householdId, personId, username) }
}

class SettingsRepository(private val api: DivvyApi) {
    suspend fun autoAssign(householdId: String): ApiResult<MlSettingsDto> = apiCall { api.getMlSettings(householdId) }

    suspend fun saveAutoAssign(householdId: String, settings: MlSettingsDto): ApiResult<MlSettingsDto> =
        apiCall { api.putMlSettings(householdId, settings) }
}

class ReceiptRepository(private val api: DivvyApi) {

    suspend fun setItemOwners(receiptId: String, itemId: String, owners: List<ItemOwnerDto>): ApiResult<ItemOwnersDto> =
        apiCall { api.setItemOwners(receiptId, itemId, owners) }

    suspend fun get(id: String): ApiResult<ReceiptDto> = apiCall { api.getReceipt(id) }

    suspend fun upload(
        householdId: String,
        jpeg: ByteArray,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): ApiResult<ReceiptUploadDto> = apiCall { api.uploadReceipt(householdId, jpeg, onProgress) }

    suspend fun confirm(id: String): ApiResult<ConfirmedReceiptDto> = apiCall { api.confirmReceipt(id) }

        suspend fun list(
        householdId: String,
        status: String? = null,
        cursor: String? = null,
        limit: Int = 50,
        merchant: String? = null,
        from: String? = null,
        to: String? = null,
        paidBy: String? = null,
    ): ApiResult<ReceiptPageDto> =
        apiCall { api.listReceipts(householdId, status, cursor, limit, merchant, from, to, paidBy) }

    suspend fun addItem(receiptId: String, item: ItemEditRequest): ApiResult<EditedReceiptDto> =
        apiCall { api.addItem(receiptId, item) }

    suspend fun updateItem(receiptId: String, itemId: String, change: ItemEditRequest): ApiResult<EditedReceiptDto> =
        apiCall { api.updateItem(receiptId, itemId, change) }

    suspend fun deleteItem(receiptId: String, itemId: String): ApiResult<EditedReceiptDto> =
        apiCall { api.deleteItem(receiptId, itemId) }

    suspend fun reopen(id: String): ApiResult<ReceiptDto> = apiCall { api.reopenReceipt(id) }

    suspend fun image(id: String): ApiResult<ByteArray> = apiCall { api.getReceiptImage(id) }

    suspend fun updateReceipt(receiptId: String, change: UpdateReceiptRequest): ApiResult<EditedReceiptDto> =
        apiCall { api.updateReceipt(receiptId, change) }

    suspend fun setPayer(receiptId: String, personId: String): ApiResult<Unit> =
        apiCall { api.setReceiptPayer(receiptId, personId) }

    suspend fun retry(householdId: String, receiptId: String): ApiResult<ReceiptUploadDto> =
        apiCall { api.retryReceipt(householdId, receiptId) }

    suspend fun predictions(householdId: String, receiptId: String): ApiResult<PredictionsDto> =
        apiCall { api.getPredictions(householdId, receiptId) }

    suspend fun acceptPredictions(
        householdId: String,
        receiptId: String,
        itemIds: List<String>? = null,
    ): ApiResult<AcceptedPredictionsDto> = apiCall { api.acceptPredictions(householdId, receiptId, itemIds) }
}

class ShoppingListRepository(private val api: DivvyApi) {
    suspend fun items(householdId: String): ApiResult<List<ListItemDto>> = apiCall { api.listItems(householdId) }

    suspend fun add(householdId: String, request: CreateListItemRequest): ApiResult<ListItemDto> =
        apiCall { api.addListItem(householdId, request) }

    suspend fun update(householdId: String, itemId: String, change: UpdateListItemRequest): ApiResult<ListItemDto> =
        apiCall { api.updateListItem(householdId, itemId, change) }

    suspend fun remove(householdId: String, itemId: String): ApiResult<Unit> =
        apiCall { api.deleteListItem(householdId, itemId) }

    suspend fun autocomplete(householdId: String, query: String): ApiResult<List<String>> =
        apiCall { api.autocompleteListItem(householdId, query).suggestions }
}

class BalanceRepository(private val api: DivvyApi) {
    suspend fun settle(householdId: String, request: CreateSettlementRequest): ApiResult<SettlementDto> =
        apiCall { api.recordSettlement(householdId, request) }

    suspend fun balances(householdId: String): ApiResult<BalancesDto> = apiCall { api.balances(householdId) }
}
