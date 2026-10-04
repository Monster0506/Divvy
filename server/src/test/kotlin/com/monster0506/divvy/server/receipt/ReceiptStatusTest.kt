package com.monster0506.divvy.server.receipt

import com.monster0506.divvy.server.ApiError
import com.monster0506.divvy.server.InvalidTransitionException
import com.monster0506.divvy.server.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReceiptStatusTest {
    private val allowed = setOf(
        ReceiptStatus.PROCESSING to ReceiptStatus.NEEDS_REVIEW,
        ReceiptStatus.PROCESSING to ReceiptStatus.FAILED,
        ReceiptStatus.NEEDS_REVIEW to ReceiptStatus.CONFIRMED,
        ReceiptStatus.NEEDS_REVIEW to ReceiptStatus.PROCESSING,
        ReceiptStatus.NEEDS_REVIEW to ReceiptStatus.FAILED,
        ReceiptStatus.CONFIRMED to ReceiptStatus.NEEDS_REVIEW,
        ReceiptStatus.FAILED to ReceiptStatus.PROCESSING,
    )

    @Test
    fun `every pair matches the transition table`() {
        for (from in ReceiptStatus.entries) {
            for (to in ReceiptStatus.entries) {
                val expected = (from to to) in allowed
                assertEquals(expected, from.canTransitionTo(to), "$from -> $to")
                if (expected) {
                    assertEquals(to, from.transitionTo(to))
                } else {
                    assertFailsWith<InvalidTransitionException>("$from -> $to") { from.transitionTo(to) }
                }
            }
        }
    }

    @Test
    fun `wire names round trip`() {
        ReceiptStatus.entries.forEach { assertEquals(it, ReceiptStatus.fromWireName(it.wireName)) }
        assertNull(ReceiptStatus.fromWireName("bogus"))
    }

    @Test
    fun `invalid transition responds with 409`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "divvy.version" to "1",
                "divvy.database.url" to "jdbc:postgresql://localhost/divvy",
                "divvy.database.user" to "divvy",
                "divvy.database.password" to "secret",
                "divvy.ml.url" to "http://localhost:8000",
                "divvy.apiKey" to "key",
            )
        }
        application {
            module()
            routing {
                get("/t") { ReceiptStatus.CONFIRMED.transitionTo(ReceiptStatus.FAILED) }
            }
        }
        val response = client.get("/t")
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("invalid_transition", error.code)
    }
}
