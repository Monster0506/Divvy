package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
private data class HouseholdInfoDto(val id: String = "", val name: String)

@Serializable
private data class MemberDto(val id: String, val name: String, val archived: Boolean = false)

@Serializable
private data class RenameBody(val name: String)

@Serializable
private data class SettingsErrorDto(val code: String = "", val message: String = "")

/** A person in the household; archived people stay in past receipts but are no longer active. */
data class HouseholdMember(val id: String, val name: String, val archived: Boolean = false)

data class HouseholdDetails(val name: String, val members: List<HouseholdMember>)

sealed interface SettingsResult<out T> {
    data class Ok<T>(val value: T) : SettingsResult<T>
    /** The server rejected the request (HTTP 400 or 409); [message] is safe to show to the user. */
    data class Invalid(val message: String) : SettingsResult<Nothing>
    /** Network or server trouble; the user can retry. */
    data class Failure(val message: String) : SettingsResult<Nothing>
}

class HouseholdSettingsApi(private val client: HttpClient) {
    suspend fun load(householdId: String): SettingsResult<HouseholdDetails> {
        val info = when (val r = call({ client.get("households/${householdId.seg()}") }) { it.body<HouseholdInfoDto>() }) {
            is SettingsResult.Ok -> r.value
            is SettingsResult.Invalid -> return r
            is SettingsResult.Failure -> return r
        }
        val people = when (
            val r = call({ client.get("households/${householdId.seg()}/people") { parameter("includeArchived", true) } }) {
                it.body<List<MemberDto>>()
            }
        ) {
            is SettingsResult.Ok -> r.value
            is SettingsResult.Invalid -> return r
            is SettingsResult.Failure -> return r
        }
        return SettingsResult.Ok(
            HouseholdDetails(info.name, people.map { HouseholdMember(it.id, it.name, it.archived) }),
        )
    }

    suspend fun rename(householdId: String, name: String): SettingsResult<Unit> =
        call({ client.patch("households/${householdId.seg()}") { setBody(RenameBody(name)) } }) { }

    suspend fun archive(householdId: String, personId: String): SettingsResult<Unit> =
        call({ client.post("households/${householdId.seg()}/people/$personId/archive") }) { }

    private suspend fun <T> call(
        request: suspend () -> HttpResponse,
        parse: suspend (HttpResponse) -> T,
    ): SettingsResult<T> {
        val response: HttpResponse = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SettingsResult.Failure("Can't reach the server. Check your connection and try again.")
        }
        return when {
            response.status.isSuccess() -> try {
                SettingsResult.Ok(parse(response))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SettingsResult.Failure("The server sent an unexpected response. Please try again.")
            }
            response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Conflict -> {
                val message = try { response.body<SettingsErrorDto>().message } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
                SettingsResult.Invalid(message.ifBlank { "The server didn't accept that change." })
            }
            else -> SettingsResult.Failure("Something went wrong on our side. Please try again.")
        }
    }
}