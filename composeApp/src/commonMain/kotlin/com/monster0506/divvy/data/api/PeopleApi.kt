package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

sealed interface CreatePersonResult {
    data class Created(val id: String) : CreatePersonResult
    /** The server rejected this name; [message] is safe to show on the row. */
    data class Invalid(val message: String) : CreatePersonResult
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : CreatePersonResult
}

class PeopleApi(client: HttpClient) {
    private val api = DivvyApi(client)

    suspend fun create(householdId: String, name: String): CreatePersonResult = try {
        CreatePersonResult.Created(api.addPerson(householdId, name).id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        val failure = e.failure
        if (failure is ApiFailure.Validation) CreatePersonResult.Invalid(failure.userMessage())
        else CreatePersonResult.Failure(failure.userMessage())
    } catch (e: Exception) {
        CreatePersonResult.Failure("The server sent an unexpected response. Please try again.")
    }
}