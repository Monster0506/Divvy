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
private data class CreatePersonBody(val name: String)

@Serializable
private data class PersonDto(val id: String, val name: String = "")

@Serializable
private data class PersonErrorDto(val code: String = "", val message: String = "")

sealed interface CreatePersonResult {
    data class Created(val id: String) : CreatePersonResult
    /** The server rejected this name (HTTP 400 or 409); [message] is safe to show on the row. */
    data class Invalid(val message: String) : CreatePersonResult
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : CreatePersonResult
}

class PeopleApi(private val client: HttpClient) {
    suspend fun create(householdId: String, name: String): CreatePersonResult {
        val response: HttpResponse = try {
            client.post("households/$householdId/people") { setBody(CreatePersonBody(name)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CreatePersonResult.Failure("Can't reach the server. Check your connection and try again.")
        }
        return when {
            response.status.isSuccess() -> try {
                CreatePersonResult.Created(response.body<PersonDto>().id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CreatePersonResult.Failure("The server sent an unexpected response. Please try again.")
            }
            response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Conflict -> {
                val message = try { response.body<PersonErrorDto>().message } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
                CreatePersonResult.Invalid(message.ifBlank { "That name isn't valid." })
            }
            else -> CreatePersonResult.Failure("Something went wrong on our side. Please try again.")
        }
    }
}