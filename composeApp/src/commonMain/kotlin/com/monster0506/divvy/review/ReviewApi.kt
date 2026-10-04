package com.monster0506.divvy.review

import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.AcceptedPredictionsDto
import com.monster0506.divvy.data.dto.ConfirmedReceiptDto
import com.monster0506.divvy.data.dto.EditedReceiptDto
import com.monster0506.divvy.data.dto.ItemEditRequest
import com.monster0506.divvy.data.dto.UpdateReceiptRequest
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptUploadDto
import com.monster0506.divvy.data.repository.HouseholdRepository
import com.monster0506.divvy.data.repository.ReceiptRepository

/** The server calls the review flow needs, so the flow can be tested without a network. */
interface ReviewApi {
    suspend fun receipt(id: String): ApiResult<ReceiptDto>

    suspend fun people(householdId: String): ApiResult<List<PersonDto>>

    suspend fun predictions(householdId: String, receiptId: String): ApiResult<PredictionsDto>

    suspend fun setPayer(receiptId: String, personId: String): ApiResult<Unit>

    suspend fun setOwners(receiptId: String, itemId: String, owners: List<ItemOwnerDto>): ApiResult<ItemOwnersDto>

    suspend fun accept(householdId: String, receiptId: String, itemIds: List<String>?): ApiResult<AcceptedPredictionsDto>

    suspend fun confirm(receiptId: String): ApiResult<ConfirmedReceiptDto>

    suspend fun updateDetails(receiptId: String, change: UpdateReceiptRequest): ApiResult<EditedReceiptDto>

    suspend fun addItem(receiptId: String, item: ItemEditRequest): ApiResult<EditedReceiptDto>

    suspend fun updateItem(receiptId: String, itemId: String, change: ItemEditRequest): ApiResult<EditedReceiptDto>

    suspend fun deleteItem(receiptId: String, itemId: String): ApiResult<EditedReceiptDto>

    suspend fun mergeItems(receiptId: String, itemIds: List<String>): ApiResult<EditedReceiptDto>

    suspend fun retry(householdId: String, receiptId: String): ApiResult<ReceiptUploadDto>
}

class RepositoryReviewApi(
    private val receipts: ReceiptRepository,
    private val households: HouseholdRepository,
) : ReviewApi {
    override suspend fun receipt(id: String) = receipts.get(id)

    override suspend fun people(householdId: String) = households.people(householdId)

    override suspend fun predictions(householdId: String, receiptId: String) =
        receipts.predictions(householdId, receiptId)

    override suspend fun setPayer(receiptId: String, personId: String): ApiResult<Unit> =
        receipts.setPayer(receiptId, personId)

    override suspend fun setOwners(receiptId: String, itemId: String, owners: List<ItemOwnerDto>) =
        receipts.setItemOwners(receiptId, itemId, owners)

    override suspend fun accept(householdId: String, receiptId: String, itemIds: List<String>?) =
        receipts.acceptPredictions(householdId, receiptId, itemIds)

    override suspend fun confirm(receiptId: String) = receipts.confirm(receiptId)

    override suspend fun updateDetails(receiptId: String, change: UpdateReceiptRequest) = receipts.updateReceipt(receiptId, change)

    override suspend fun addItem(receiptId: String, item: ItemEditRequest) = receipts.addItem(receiptId, item)

    override suspend fun updateItem(receiptId: String, itemId: String, change: ItemEditRequest) =
        receipts.updateItem(receiptId, itemId, change)

    override suspend fun deleteItem(receiptId: String, itemId: String) = receipts.deleteItem(receiptId, itemId)

    override suspend fun mergeItems(receiptId: String, itemIds: List<String>) = receipts.mergeItems(receiptId, itemIds)

    override suspend fun retry(householdId: String, receiptId: String) = receipts.retry(householdId, receiptId)
}
