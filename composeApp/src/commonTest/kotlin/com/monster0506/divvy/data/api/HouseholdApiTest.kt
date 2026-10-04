package com.monster0506.divvy.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HouseholdApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: MockRequestHandler) =
        HouseholdApi(createHttpClient(ApiConfig("http://test", "key"), MockEngine(handler)))

    @Test
    fun postsNameAndReturnsId() = runTest {
        var seen: HttpRequestData? = null
        val result = api { request ->
            seen = request
            respond("""{"id":"abc","name":"Home","defaultCurrency":"USD"}""", HttpStatusCode.Created, json)
        }.create("Home")
        assertEquals(CreateHouseholdResult.Created("abc"), result)
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/households", seen!!.url.encodedPath)
        assertEquals("key", seen!!.headers[API_KEY_HEADER])
        assertTrue((seen!!.body as TextContent).text.contains("\"name\":\"Home\""))
    }

    @Test
    fun badRequestBecomesInvalid() = runTest {
        val result = api {
            respond("""{"code":"validation_error","message":"name must be 1-80 characters"}""", HttpStatusCode.BadRequest, json)
        }.create("x")
        assertEquals(CreateHouseholdResult.Invalid("name must be 1-80 characters"), result)
    }

    @Test
    fun serverErrorBecomesFailure() = runTest {
        val result = api { respond("""{"code":"internal_error","message":"boom"}""", HttpStatusCode.InternalServerError, json) }.create("x")
        assertTrue(result is CreateHouseholdResult.Failure)
    }

    @Test
    fun noConnectionBecomesFailure() = runTest {
        val result = api { error("offline") }.create("x")
        assertTrue(result is CreateHouseholdResult.Failure)
    }
}