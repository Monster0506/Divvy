package com.monster0506.divvy.data.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HouseholdPathTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aHouseholdNameWithSpacesAndSlashesStaysOneSegment() = runTest {
        var seen: HttpRequestData? = null
        val client = createHttpClient(
            ApiConfig("http://test", "key"),
            MockEngine { request ->
                seen = request
                respond("""{"id":"p1","name":"Sam"}""", HttpStatusCode.Created, json)
            },
        )
        PeopleApi(client).create("Mom & Dad's / Place?", "Sam")

        val path = seen!!.url.encodedPath
        assertEquals("/households/Mom%20&%20Dad's%20%2F%20Place%3F/people", path)
    }
}
