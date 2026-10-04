package com.monster0506.divvy.data.api

import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.AutocompleteDto
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.CreateListItemRequest
import com.monster0506.divvy.data.dto.ListItemDto
import com.monster0506.divvy.data.dto.UpdateListItemRequest
import com.monster0506.divvy.data.dto.AcceptPredictionsRequest
import com.monster0506.divvy.data.dto.AcceptedPredictionsDto
import com.monster0506.divvy.data.dto.ConfirmedReceiptDto
import com.monster0506.divvy.data.dto.CreateHouseholdRequest
import com.monster0506.divvy.data.dto.CreateSettlementRequest
import com.monster0506.divvy.data.dto.SettlementDto
import com.monster0506.divvy.data.dto.EditedReceiptDto
import com.monster0506.divvy.data.dto.ItemEditRequest
import com.monster0506.divvy.data.dto.MergeItemsRequest
import com.monster0506.divvy.data.dto.MlSettingsDto
import com.monster0506.divvy.data.dto.ReceiptPageDto
import com.monster0506.divvy.data.dto.UpdateReceiptRequest
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.SetReceiptPayerRequest
import com.monster0506.divvy.data.dto.HouseholdDto
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.PersonRequest
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptUploadDto
import com.monster0506.divvy.data.dto.RenameHouseholdRequest
import com.monster0506.divvy.data.dto.SetItemOwnersRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException

/** Thrown by [DivvyApi] for any failed call; repositories turn it into an [ApiResult]. */
class ApiException(val failure: ApiFailure) : Exception(failure.toString())

data class HouseholdCreationResponse(val household: HouseholdDto, val alreadyExisted: Boolean)

/** Typed wrappers over the raw HTTP calls. */
class DivvyApi(private val client: HttpClient) {
    suspend fun createHousehold(name: String): HouseholdDto =
        createHouseholdWithStatus(name).household

    /** Preserves the server's 200 (existing household) versus 201 (new household) result for onboarding. */
    suspend fun createHouseholdWithStatus(name: String): HouseholdCreationResponse {
        val response = execute {
            client.post("households") { contentType(ContentType.Application.Json); setBody(CreateHouseholdRequest(name)) }
        }
        if (!response.status.isSuccess()) throw ApiException(response.toFailure())
        val household = try {
            response.body<HouseholdDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentConvertException) {
            throw ApiException(ApiFailure.ServerError(response.status.value))
        }
        return HouseholdCreationResponse(household, response.status == HttpStatusCode.OK)
    }

    suspend fun getHousehold(id: String): HouseholdDto = send { client.get("households/${id.seg()}") }

    suspend fun renameHousehold(id: String, name: String): HouseholdDto =
        send { client.patch("households/${id.seg()}") { contentType(ContentType.Application.Json); setBody(RenameHouseholdRequest(name)) } }

    suspend fun listPeople(householdId: String, includeArchived: Boolean = false): List<PersonDto> =
        send { client.get("households/${householdId.seg()}/people?includeArchived=$includeArchived") }

    suspend fun addPerson(householdId: String, displayName: String): PersonDto =
        send { client.post("households/${householdId.seg()}/people") { contentType(ContentType.Application.Json); setBody(PersonRequest(displayName)) } }

    suspend fun renamePerson(householdId: String, personId: String, displayName: String): PersonDto =
        send { client.patch("households/${householdId.seg()}/people/$personId") { contentType(ContentType.Application.Json); setBody(PersonRequest(displayName)) } }

    suspend fun archivePerson(householdId: String, personId: String): PersonDto =
        send { client.post("households/${householdId.seg()}/people/$personId/archive") }

    suspend fun getReceipt(id: String): ReceiptDto = send { client.get("receipts/$id") }

    suspend fun uploadReceipt(
        householdId: String,
        jpeg: ByteArray,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): ReceiptUploadDto = send {
        client.post("households/${householdId.seg()}/receipts") {
            setBody(MultiPartFormDataContent(formData {
                append("image", jpeg, Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"receipt.jpg\"")
                })
            }))
            onUpload { sent, total -> onProgress(sent, total ?: -1) }
        }
    }

    suspend fun confirmReceipt(id: String): ConfirmedReceiptDto = send { client.post("receipts/$id/confirm") }

    /** One page of the household's receipts, newest first. [status] limits it to one status, for example `draft`. */
    suspend fun listReceipts(
        householdId: String,
        status: String? = null,
        cursor: String? = null,
        limit: Int = 50,
        merchant: String? = null,
        from: String? = null,
        to: String? = null,
        paidBy: String? = null,
    ): ReceiptPageDto = send {
        client.get("households/${householdId.seg()}/receipts") {
            parameter("limit", limit)
            status?.let { parameter("status", it) }
            cursor?.let { parameter("cursor", it) }
            merchant?.let { parameter("merchant", it) }
            from?.let { parameter("from", it) }
            to?.let { parameter("to", it) }
            paidBy?.let { parameter("paidBy", it) }
        }
    }
    suspend fun addItem(receiptId: String, item: ItemEditRequest): EditedReceiptDto =
        send { client.post("receipts/$receiptId/items") { setBody(item) } }

    suspend fun updateItem(receiptId: String, itemId: String, change: ItemEditRequest): EditedReceiptDto =
        send { client.patch("receipts/$receiptId/items/$itemId") { setBody(change) } }

    suspend fun deleteItem(receiptId: String, itemId: String): EditedReceiptDto =
        send { client.delete("receipts/$receiptId/items/$itemId") }

    /** Combines items into the first one (lowest line), summing their prices. */
    suspend fun mergeItems(receiptId: String, itemIds: List<String>, description: String? = null): EditedReceiptDto =
        send { client.post("receipts/$receiptId/items/merge") { setBody(MergeItemsRequest(itemIds, description)) } }

    suspend fun getMlSettings(householdId: String): MlSettingsDto =
        send { client.get("households/${householdId.seg()}/ml/settings") }

    suspend fun putMlSettings(householdId: String, settings: MlSettingsDto): MlSettingsDto =
        send { client.put("households/${householdId.seg()}/ml/settings") { setBody(settings) } }

    /** Moves a confirmed receipt back to a draft so it can be corrected. */
    suspend fun reopenReceipt(id: String): ReceiptDto = send { client.post("receipts/$id/reopen") }

    /** The receipt's photo exactly as it was uploaded. */
    suspend fun getReceiptImage(id: String): ByteArray = send { client.get("receipts/$id/image") }

    /** Changes the receipt's store, date, tax or total and returns it with how its lines now compare. */
    suspend fun updateReceipt(receiptId: String, change: UpdateReceiptRequest): EditedReceiptDto =
        send { client.patch("receipts/$receiptId") { setBody(change) } }

    /** Records who paid. The response is not needed, only that the change was accepted. */
    suspend fun setReceiptPayer(receiptId: String, personId: String) {
        send<kotlinx.serialization.json.JsonElement> {
            client.patch("receipts/$receiptId") { setBody(SetReceiptPayerRequest(personId)) }
        }
    }

    /** Reads a receipt photo again after it failed. */
    suspend fun retryReceipt(householdId: String, receiptId: String): ReceiptUploadDto =
        send { client.post("households/${householdId.seg()}/receipts/$receiptId/retry") }

    suspend fun getPredictions(householdId: String, receiptId: String): PredictionsDto =
        send { client.get("households/${householdId.seg()}/receipts/$receiptId/predictions") }

    /** Keeps the model's owners as the person's choice, for [itemIds] or every item that needs it when null. */
    suspend fun acceptPredictions(householdId: String, receiptId: String, itemIds: List<String>? = null): AcceptedPredictionsDto =
        send {
            client.post("households/${householdId.seg()}/receipts/$receiptId/predictions/accept") {
                setBody(AcceptPredictionsRequest(itemIds))
            }
        }

    suspend fun setItemOwners(receiptId: String, itemId: String, owners: List<ItemOwnerDto>): ItemOwnersDto =
        send { client.put("receipts/$receiptId/items/$itemId/owners") { setBody(SetItemOwnersRequest(owners)) } }

    /** Records that [request].fromPersonId paid toPersonId, which lowers what the first owes the second. */
    suspend fun recordSettlement(householdId: String, request: CreateSettlementRequest): SettlementDto =
        send { client.post("households/${householdId.seg()}/settlements") { setBody(request) } }

    suspend fun balances(householdId: String): BalancesDto =
        send { client.get("households/${householdId.seg()}/balances") }

    /** The shared grocery list: open items first, then purchased ones. */
    suspend fun listItems(householdId: String): List<ListItemDto> =
        send { client.get("households/${householdId.seg()}/list") }

    suspend fun addListItem(householdId: String, request: CreateListItemRequest): ListItemDto =
        send { client.post("households/${householdId.seg()}/list") { setBody(request) } }

    suspend fun updateListItem(householdId: String, itemId: String, change: UpdateListItemRequest): ListItemDto =
        send { client.patch("households/${householdId.seg()}/list/$itemId") { setBody(change) } }

    suspend fun deleteListItem(householdId: String, itemId: String) {
        send<Unit> { client.delete("households/${householdId.seg()}/list/$itemId") }
    }

    /** Names the household has bought before that contain [query]. */
    suspend fun autocompleteListItem(householdId: String, query: String): AutocompleteDto =
        send { client.get("households/${householdId.seg()}/list/autocomplete") { parameter("q", query) } }

    private suspend inline fun <reified T> send(noinline request: suspend () -> HttpResponse): T {
        val response = execute(request)
        if (!response.status.isSuccess()) throw ApiException(response.toFailure())
        return try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentConvertException) {
            throw ApiException(ApiFailure.ServerError(response.status.value))
        }
    }

    private suspend fun execute(request: suspend () -> HttpResponse): HttpResponse = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ApiException(ApiFailure.Offline)
    }
}

private suspend fun HttpResponse.toFailure(): ApiFailure = when {
    status == HttpStatusCode.Unauthorized -> ApiFailure.Unauthorised
    status.value in 400..499 -> ApiFailure.Validation(readError())
    else -> ApiFailure.ServerError(status.value)
}

private suspend fun HttpResponse.readError(): ApiError =
    try {
        body<ApiError>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiError("http_${status.value}", status.description)
    }
