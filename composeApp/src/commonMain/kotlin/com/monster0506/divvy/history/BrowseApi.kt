package com.monster0506.divvy.history

import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptPageDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.data.repository.HouseholdRepository
import com.monster0506.divvy.data.repository.ReceiptRepository

/** One request for a page of the household's receipts. [from] and [to] are `YYYY-MM-DD`. */
data class ReceiptQuery(
    val cursor: String? = null,
    val status: ReceiptStatusDto? = null,
    val merchant: String? = null,
    val from: String? = null,
    val to: String? = null,
    val limit: Int = 50,
)

/** The server calls the receipt list and detail screens need, so they can be tested without a network. */
interface BrowseApi {
    suspend fun receipts(householdId: String, query: ReceiptQuery = ReceiptQuery()): ApiResult<ReceiptPageDto>

    suspend fun receipt(id: String): ApiResult<ReceiptDto>

    suspend fun people(householdId: String): ApiResult<List<PersonDto>>

    /** Moves a confirmed receipt back to a draft. */
    suspend fun reopen(id: String): ApiResult<ReceiptDto>

    suspend fun image(id: String): ApiResult<ByteArray>
}

class RepositoryBrowseApi(
    private val receipts: ReceiptRepository,
    private val households: HouseholdRepository,
) : BrowseApi {
    override suspend fun receipts(householdId: String, query: ReceiptQuery) =
        receipts.list(
            householdId,
            status = query.status?.name?.lowercase(),
            cursor = query.cursor,
            limit = query.limit,
            merchant = query.merchant,
            from = query.from,
            to = query.to,
        )

    override suspend fun receipt(id: String) = receipts.get(id)

    override suspend fun people(householdId: String) = households.people(householdId)

    override suspend fun reopen(id: String) = receipts.reopen(id)

    override suspend fun image(id: String) = receipts.image(id)
}