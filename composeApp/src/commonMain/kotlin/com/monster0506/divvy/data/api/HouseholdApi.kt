package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
private data class CreateHouseholdBody(val name: String)

@Serializable
private data class HouseholdDto(val id: String, val name: String = "")

@Serializable
private data class ApiErrorDto(val code: String = "", val message: String = "")

sealed interface CreateHouseholdResult {
    data class Created(val id: String) : CreateHouseholdResult
    /** The server rejected the name (HTTP 400); [message] is safe to show next to the field. */
    data class Invalid(val message: String) : CreateHouseholdResult
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : CreateHouseholdResult
}

class HouseholdApi(private val client: HttpClient) {
    suspend fun create(name: String): CreateHouseholdResult {
        val response: HttpResponse = try {
            client.post("households") { setBody(CreateHouseholdBody(name)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CreateHouseholdResult.Failure("Can't reach the server. Check your connection and try again.")
        }
        return when {
            response.status.isSuccess() -> try {
                CreateHouseholdResult.Created(response.body<HouseholdDto>().name.ifBlank { name.trim() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CreateHouseholdResult.Failure("The server sent an unexpected response. Please try again.")
            }
            response.status == HttpStatusCode.BadRequest -> {
                val message = try { response.body<ApiErrorDto>().message } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
                CreateHouseholdResult.Invalid(message.ifBlank { "That name isn't valid." })
            }
            else -> CreateHouseholdResult.Failure("Something went wrong on our side. Please try again.")
        }
    }
}