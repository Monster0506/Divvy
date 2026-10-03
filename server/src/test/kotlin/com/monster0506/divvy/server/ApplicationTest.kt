package com.monster0506.divvy.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {
    private fun Application.testModule() {
        module()
        routing {
            get("/boom") { error("secret internal detail") }
            get("/invalid") { throw ValidationException("Name is required", listOf("name")) }
            get("/missing") { throw NotFoundException("Household not found") }
        }
    }

    @Test
    fun moduleStarts() = testApplication {
        application { module() }
        startApplication()
    }

    @Test
    fun unexpectedExceptionReturns500WithoutLeakingDetails() = testApplication {
        application { testModule() }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("secret internal detail"))
        assertEquals("internal_error", Json.decodeFromString<ApiError>(body).code)
    }

    @Test
    fun validationExceptionReturns400() = testApplication {
        application { testModule() }
        val response = client.get("/invalid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("Name is required", error.message)
        assertEquals(listOf("name"), error.details)
    }

    @Test
    fun notFoundExceptionReturns404() = testApplication {
        application { testModule() }
        val response = client.get("/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not_found"))
    }
}
