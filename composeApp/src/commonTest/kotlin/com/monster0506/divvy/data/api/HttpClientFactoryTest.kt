package com.monster0506.divvy.data.api

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HttpClientFactoryTest {
    @Serializable
    private data class Health(val status: String)

    @Test
    fun requests_use_base_url_and_api_key() = runTest {
        var seenUrl = ""
        var seenKey: String? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenKey = request.headers[API_KEY_HEADER]
            respond(
                """{"status":"ok","extra":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createHttpClient(ApiConfig("http://example.test:9000/", "secret"), engine)

        val health: Health = client.get("health").body()

        assertEquals("http://example.test:9000/health", seenUrl)
        assertEquals("secret", seenKey)
        assertEquals("ok", health.status)
    }

    @Test
    fun connection_problem_is_reported_for_network_errors() = runTest {
        var problems = 0
        val client = createHttpClient(ApiConfig("http://test", "key"), MockEngine { error("refused") }) { problems++ }
        assertFails { client.get("anything") }
        assertEquals(1, problems)
    }

    @Test
    fun connection_problem_is_reported_for_401_only() = runTest {
        var problems = 0
        var status = HttpStatusCode.Unauthorized
        val client = createHttpClient(ApiConfig("http://test", "key"), MockEngine { respond("", status) }) { problems++ }
        client.get("a")
        assertEquals(1, problems)
        status = HttpStatusCode.NotFound
        client.get("b")
        status = HttpStatusCode.InternalServerError
        client.get("c")
        assertEquals(1, problems, "other error statuses are the screen's business")
    }
}
