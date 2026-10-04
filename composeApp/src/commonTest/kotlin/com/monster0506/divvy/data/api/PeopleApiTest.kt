package com.monster0506.divvy.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: MockRequestHandler) =
        PeopleApi(createHttpClient(ApiConfig("http://test", "key"), MockEngine(handler)))

    @Test
    fun postsDisplayNameTheServerExpects() = runTest {
        var seen: HttpRequestData? = null
        val result = api { request ->
            seen = request
            respond(
                """{"id":"p1","householdId":"h1","displayName":"Sam","archivedAt":null,"createdAt":"t","updatedAt":"t"}""",
                HttpStatusCode.Created,
                json,
            )
        }.create("h1", "Sam")
        assertEquals(CreatePersonResult.Created("p1"), result)
        assertEquals(HttpMethod.Post, seen!!.method)
        assertEquals("/households/h1/people", seen!!.url.encodedPath)
        assertTrue((seen!!.body as TextContent).text.contains("\"displayName\":\"Sam\""))
    }

    @Test
    fun badRequestBecomesInvalid() = runTest {
        val result = api {
            respond("""{"code":"validation_error","message":"displayName must be between 1 and 80 characters"}""", HttpStatusCode.BadRequest, json)
        }.create("h1", "x")
        assertEquals(CreatePersonResult.Invalid("displayName must be between 1 and 80 characters"), result)
    }
}
