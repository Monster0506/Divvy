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

sealed interface SettingsResult<out T> {
    data class Ok<T>(val value: T) : SettingsResult<T>
    /** The server rejected the request; [message] is safe to show to the user. */
    data class Invalid(val message: String) : SettingsResult<Nothing>
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : SettingsResult<Nothing>
}

/** The household's members for Account, with their Venmo usernames. */
class HouseholdSettingsApi(client: HttpClient) {
    private val api = DivvyApi(client)

    /** Every member, archived ones included. */
    suspend fun members(householdId: String): SettingsResult<List<HouseholdMember>> = settingsCall {
        api.listPeople(householdId, includeArchived = true)
            .map { HouseholdMember(it.id, it.displayName, it.isArchived, it.venmoUsername) }
    }

    /** Saves [personId]'s Venmo username, or removes it when [username] is empty. Returns it as the server stored it. */
    suspend fun setVenmo(householdId: String, personId: String, username: String): SettingsResult<String?> =
        when (val result = settingsCall { api.setVenmoUsername(householdId, personId, username).venmoUsername }) {
            is SettingsResult.Invalid -> SettingsResult.Invalid(venmoNotSupported(result.message) ?: result.message)
            else -> result
        }
}

/** Shown when the server is older than Venmo usernames and so demands a name on every person update. */
const val VENMO_SERVER_TOO_OLD =
    "This server doesn't support Venmo usernames yet. Update the Divvy server, then try again."

/** [VENMO_SERVER_TOO_OLD] if [message] is an older server's reply to a Venmo-only update, else null. */
fun venmoNotSupported(message: String): String? =
    VENMO_SERVER_TOO_OLD.takeIf { message.startsWith("displayName must be") }

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