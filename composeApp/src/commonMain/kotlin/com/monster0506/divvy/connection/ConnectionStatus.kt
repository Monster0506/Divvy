package com.monster0506.divvy.connection

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Whether the app can use the configured server. */
enum class ConnectionStatus {
    /** Not checked yet. */
    Unknown,

    /** The server answered and accepted the API key. */
    Online,

    /** No answer, or something other than a Divvy server answered at that address. */
    Unreachable,

    /** The server answered but rejected the API key. */
    KeyRejected,
}

/**
 * Checks `GET /health` (public), then `GET /auth/check` (needs the API key) to tell a wrong
 * address apart from a wrong key. A server too old to have `/auth/check` answers 404, which
 * counts as [ConnectionStatus.Online] because the key can't be tested any other way.
 */
suspend fun HttpClient.checkConnection(): ConnectionStatus {
    val health = getOrNull("health") ?: return ConnectionStatus.Unreachable
    if (!health.status.isSuccess()) return ConnectionStatus.Unreachable
    val auth = getOrNull("auth/check") ?: return ConnectionStatus.Unreachable
    return if (auth.status == HttpStatusCode.Unauthorized) ConnectionStatus.KeyRejected else ConnectionStatus.Online
}

private suspend fun HttpClient.getOrNull(path: String) = try {
    get(path)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    null
}
