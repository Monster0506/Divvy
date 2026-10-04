package com.monster0506.divvy.platform

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finds the numeric ID behind a Venmo username, which Venmo's app links need to find the person. Venmo's public profile
 * page (account.venmo.com/u/<username>) carries it. IDs found are remembered for the session.
 */
class VenmoUserIds(
    /** A plain client, never the Divvy one, so the household API key is not sent to Venmo. */
    private val client: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 8_000
        }
    },
) {
    private val found = mutableMapOf<String, String>()

    /** [username]'s Venmo ID, or null if Venmo has no such user or could not be reached. */
    suspend fun idFor(username: String): String? {
        val name = normalizeVenmoUsername(username)
        if (name.isEmpty()) return null
        found[name.lowercase()]?.let { return it }
        val page = try {
            client.get("https://account.venmo.com/u/${name.encodeURLPathPart()}").takeIf { it.status.isSuccess() }?.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return venmoUserIdFromProfile(page, name)?.also { found[name.lowercase()] = it }
    }
}

private val nextData = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

/** The ID in a Venmo profile page for [username], or null if the page is for someone else or has none. */
fun venmoUserIdFromProfile(page: String, username: String): String? {
    val data = nextData.find(page)?.groupValues?.get(1) ?: return null
    val user = try {
        (((Json.parseToJsonElement(data) as? JsonObject)?.get("props") as? JsonObject)?.get("pageProps") as? JsonObject)
            ?.get("user") as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val pageUsername = runCatching { user["username"]?.jsonPrimitive?.content }.getOrNull()
    if (!pageUsername.equals(normalizeVenmoUsername(username), ignoreCase = true)) return null
    return runCatching { user["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}
