package com.monster0506.divvy.data.api

import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.BalanceDto
import com.monster0506.divvy.data.dto.CreateHouseholdRequest
import com.monster0506.divvy.data.dto.HouseholdDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.PersonRequest
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.RenameHouseholdRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException

/** Thrown by [DivvyApi] for any failed call; repositories turn it into an [ApiResult]. */
class ApiException(val failure: ApiFailure) : Exception(failure.toString())

/** Typed wrappers over the raw HTTP calls. */
class DivvyApi(private val client: HttpClient) {
    suspend fun createHousehold(name: String): HouseholdDto =
        send { client.post("households") { setBody(CreateHouseholdRequest(name)) } }

    suspend fun getHousehold(id: String): HouseholdDto = send { client.get("households/$id") }

    suspend fun renameHousehold(id: String, name: String): HouseholdDto =
        send { client.patch("households/$id") { setBody(RenameHouseholdRequest(name)) } }

    suspend fun listPeople(householdId: String, includeArchived: Boolean = false): List<PersonDto> =
        send { client.get("households/$householdId/people?includeArchived=$includeArchived") }

    suspend fun addPerson(householdId: String, displayName: String): PersonDto =
        send { client.post("households/$householdId/people") { setBody(PersonRequest(displayName)) } }

    suspend fun renamePerson(householdId: String, personId: String, displayName: String): PersonDto =
        send { client.patch("households/$householdId/people/$personId") { setBody(PersonRequest(displayName)) } }

    suspend fun archivePerson(householdId: String, personId: String): PersonDto =
        send { client.post("households/$householdId/people/$personId/archive") }

    suspend fun getReceipt(id: String): ReceiptDto = send { client.get("receipts/$id") }

    suspend fun confirmReceipt(id: String): ReceiptDto = send { client.post("receipts/$id/confirm") }

    suspend fun balances(householdId: String): List<BalanceDto> =
        send { client.get("households/$householdId/balances") }

    private suspend inline fun <reified T> send(request: () -> HttpResponse): T {
        val response = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ApiException(ApiFailure.Offline)
        }
        if (!response.status.isSuccess()) throw ApiException(response.toFailure())
        return try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContentConvertException) {
            throw ApiException(ApiFailure.ServerError(response.status.value))
        }
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
