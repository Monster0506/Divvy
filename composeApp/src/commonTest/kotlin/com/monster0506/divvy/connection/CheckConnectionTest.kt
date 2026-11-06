package com.monster0506.divvy.connection

import com.monster0506.divvy.data.api.API_KEY_HEADER
import com.monster0506.divvy.data.api.ApiConfig
import com.monster0506.divvy.data.api.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckConnectionTest {
    private val requested = mutableListOf<String>()

    private suspend fun check(health: HttpStatusCode, auth: HttpStatusCode = HttpStatusCode.NoContent): ConnectionStatus {
        val client = createHttpClient(
            ApiConfig("http://test", "key"),
            MockEngine { request ->
                requested += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/health" -> respond("""{"status":"ok"}""", health)
                    "/auth/check" -> respond(
                        "",
                        if (request.headers[API_KEY_HEADER] == "key") auth else HttpStatusCode.Unauthorized,
                    )
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        return client.use { it.checkConnection() }
    }

    @Test
    fun healthyServerWithGoodKeyIsOnline() = runTest {
        assertEquals(ConnectionStatus.Online, check(HttpStatusCode.OK))
        assertEquals(listOf("/health", "/auth/check"), requested)
    }

    @Test
    fun rejectedKeyIsReported() = runTest {
        assertEquals(ConnectionStatus.KeyRejected, check(HttpStatusCode.OK, auth = HttpStatusCode.Unauthorized))
    }

    @Test
    fun serverWithoutAuthCheckCountsAsOnline() = runTest {
        assertEquals(ConnectionStatus.Online, check(HttpStatusCode.OK, auth = HttpStatusCode.NotFound))
    }

    @Test
    fun nonDivvyAnswerAtHealthIsUnreachable() = runTest {
        assertEquals(ConnectionStatus.Unreachable, check(HttpStatusCode.NotFound))
        assertEquals(listOf("/health"), requested, "the key isn't tested against the wrong server")
    }

    @Test
    fun networkErrorIsUnreachable() = runTest {
        val client = createHttpClient(ApiConfig("http://test", "key"), MockEngine { error("connection refused") })
        assertEquals(ConnectionStatus.Unreachable, client.use { it.checkConnection() })
    }

    /** A fresh release install has no key; servers before #309 answered an empty key header with a 500. */
    @Test
    fun noKeyIsReportedSoTheAppAsksForIt() = runTest {
        val client = createHttpClient(
            ApiConfig("http://test", ""),
            MockEngine { request ->
                when {
                    request.url.encodedPath == "/health" -> respond("""{"status":"ok"}""", HttpStatusCode.OK)
                    API_KEY_HEADER in request.headers -> respond("", HttpStatusCode.InternalServerError)
                    else -> respond("", HttpStatusCode.Unauthorized)
                }
            },
        )
        assertEquals(ConnectionStatus.KeyRejected, client.use { it.checkConnection() })
    }
}
