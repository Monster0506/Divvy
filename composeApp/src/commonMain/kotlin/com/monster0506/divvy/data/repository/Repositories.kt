package com.monster0506.divvy.data.repository

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.api.DivvyApi
import com.monster0506.divvy.data.dto.BalanceDto
import com.monster0506.divvy.data.dto.HouseholdDto
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
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
}

class ReceiptRepository(private val api: DivvyApi) {

    suspend fun setItemOwners(receiptId: String, itemId: String, owners: List<ItemOwnerDto>): ApiResult<ItemOwnersDto> =
        apiCall { api.setItemOwners(receiptId, itemId, owners) }

    suspend fun get(id: String): ApiResult<ReceiptDto> = apiCall { api.getReceipt(id) }

    suspend fun confirm(id: String): ApiResult<ReceiptDto> = apiCall { api.confirmReceipt(id) }
}

class BalanceRepository(private val api: DivvyApi) {
    suspend fun balances(householdId: String): ApiResult<List<BalanceDto>> = apiCall { api.balances(householdId) }
}
