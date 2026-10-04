package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

sealed interface CreateHouseholdResult {
    data class Created(val id: String) : CreateHouseholdResult
    data class Existing(val id: String) : CreateHouseholdResult
    /** The server rejected the name; [message] is safe to show next to the field. */
    data class Invalid(val message: String) : CreateHouseholdResult
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : CreateHouseholdResult
}

class HouseholdApi(client: HttpClient) {
    private val api = DivvyApi(client)

    suspend fun create(name: String): CreateHouseholdResult = try {
        val response = api.createHouseholdWithStatus(name)
        val id = response.household.name.ifBlank { name.trim() }
        if (response.alreadyExisted) CreateHouseholdResult.Existing(id) else CreateHouseholdResult.Created(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        val failure = e.failure
        if (failure is ApiFailure.Validation) CreateHouseholdResult.Invalid(failure.userMessage())
        else CreateHouseholdResult.Failure(failure.userMessage())
    } catch (e: Exception) {
        CreateHouseholdResult.Failure("The server sent an unexpected response. Please try again.")
    }

    suspend fun members(householdId: String): List<HouseholdMember> =
        api.listPeople(householdId).filter { it.archivedAt == null }.map { HouseholdMember(it.id, it.displayName) }
}
