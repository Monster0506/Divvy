package com.monster0506.divvy.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shape of Venmo's public profile page, trimmed to what matters. */
private fun profile(username: String, id: String) =
    """<html><body><div id="__next"></div><script id="__NEXT_DATA__" type="application/json">""" +
        """{"props":{"pageProps":{"user":{"displayName":"Savar Shrestha","id":"$id","username":"$username",""" +
        """"friendCount":0}}},"page":"/u/[username]"}</script></body></html>"""

class VenmoUserIdsTest {
    @Test
    fun the_id_is_read_from_the_profile_page() {
        assertEquals("4669641469200166362", venmoUserIdFromProfile(profile("Savar-Shrestha", "4669641469200166362"), "@savar-shrestha"))
    }

    @Test
    fun a_page_for_someone_else_or_without_an_id_gives_nothing() {
        assertNull(venmoUserIdFromProfile(profile("Someone-Else", "1"), "Savar-Shrestha"))
        assertNull(venmoUserIdFromProfile(profile("Savar-Shrestha", "not-a-number"), "Savar-Shrestha"))
        assertNull(venmoUserIdFromProfile("<html>Sign in</html>", "Savar-Shrestha"))
        assertNull(venmoUserIdFromProfile("""<script id="__NEXT_DATA__" type="application/json">{oops</script>""", "Savar-Shrestha"))
    }

    @Test
    fun ids_are_fetched_once_per_username_and_without_the_divvy_api_key() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.toString()
            assertNull(request.headers["X-Api-Key"])
            respond(profile("Savar-Shrestha", "466"))
        }
        val ids = VenmoUserIds(HttpClient(engine))

        assertEquals("466", ids.idFor("@Savar-Shrestha"))
        assertEquals("466", ids.idFor("savar-shrestha"))

        assertEquals(listOf("https://account.venmo.com/u/Savar-Shrestha"), requests)
    }

    @Test
    fun an_unknown_user_or_unreachable_venmo_gives_no_id() = runTest {
        val missing = VenmoUserIds(HttpClient(MockEngine { respond("Not found", HttpStatusCode.NotFound) }))
        assertNull(missing.idFor("no-such-user"))

        val offline = VenmoUserIds(HttpClient(MockEngine { throw RuntimeException("offline") }))
        assertNull(offline.idFor("Savar-Shrestha"))
    }
}
