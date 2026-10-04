package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

/** A person in the household; archived people stay in past receipts but are no longer active. */
data class HouseholdMember(
    val id: String,
    val name: String,
    val archived: Boolean = false,
    /** Where they receive payments, without the "@"; null if they have not said. */
    val venmoUsername: String? = null,
)

data class HouseholdDetails(val name: String, val members: List<HouseholdMember>)

sealed interface SettingsResult<out T> {
    data class Ok<T>(val value: T) : SettingsResult<T>
    /** The server rejected the request; [message] is safe to show to the user. */
    data class Invalid(val message: String) : SettingsResult<Nothing>
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : SettingsResult<Nothing>
}

class HouseholdSettingsApi(client: HttpClient) {
    private val api = DivvyApi(client)

    suspend fun load(householdId: String): SettingsResult<HouseholdDetails> = settingsCall {
        val household = api.getHousehold(householdId)
        val people = api.listPeople(householdId, includeArchived = true)
        HouseholdDetails(household.name, people.map { HouseholdMember(it.id, it.displayName, it.isArchived, it.venmoUsername) })
    }

    /** Saves [personId]'s Venmo username, or removes it when [username] is empty. Returns it as the server stored it. */
    suspend fun setVenmo(householdId: String, personId: String, username: String): SettingsResult<String?> = settingsCall {
        api.setVenmoUsername(householdId, personId, username).venmoUsername
    }

    suspend fun rename(householdId: String, name: String): SettingsResult<Unit> = settingsCall {
        api.renameHousehold(householdId, name)
        Unit
    }

    suspend fun archive(householdId: String, personId: String): SettingsResult<Unit> = settingsCall {
        api.archivePerson(householdId, personId)
        Unit
    }
}

private suspend fun <T> settingsCall(block: suspend () -> T): SettingsResult<T> = try {
    SettingsResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    val failure = e.failure
    if (failure is ApiFailure.Validation) SettingsResult.Invalid(failure.userMessage())
    else SettingsResult.Failure(failure.userMessage())
} catch (e: Exception) {
    SettingsResult.Failure("The server sent an unexpected response. Please try again.")
}