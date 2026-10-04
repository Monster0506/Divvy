package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HouseholdRoutesTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            routing { householdRoutes { repos.households } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `create returns 201 with the new household`() = withApp {
        val res = post("/households", """{"name":"  Flat 4B  "}""")
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("Flat 4B", res.field("name"))
        assertEquals("USD", res.field("defaultCurrency"))
        assertNotEquals("", res.field("id"))
    }

    @Test
    fun `get returns the created household`() = withApp {
        val id = post("/households", """{"name":"Home","defaultCurrency":"eur"}""").field("id")
        val res = client.get("/households/$id")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Home", res.field("name"))
        assertEquals("EUR", res.field("defaultCurrency"))
    }

    @Test
    fun `patch renames the household`() = withApp {
        val id = post("/households", """{"name":"Old"}""").field("id")
        val res = patch("/households/$id", """{"name":"New"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("New", res.field("name"))
        assertEquals("New", client.get("/households/$id").field("name"))
    }

    @Test
    fun `unknown id returns 404 for get and patch`() = withApp {
        val unknown = "00000000-0000-0000-0000-000000000001"
        assertEquals(HttpStatusCode.NotFound, client.get("/households/$unknown").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$unknown", """{"name":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/not-a-uuid").status)
    }

    @Test
    fun `invalid names are rejected with 400`() = withApp {
        assertEquals(HttpStatusCode.BadRequest, post("/households", """{"name":""}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households", """{"name":"   "}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households", "not json").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households", """{"name":"${"a".repeat(81)}"}""").status)
        assertEquals(HttpStatusCode.Created, post("/households", """{"name":"${"a".repeat(80)}"}""").status)
    }

    @Test
    fun `patch with an invalid name returns 400`() = withApp {
        val id = post("/households", """{"name":"Home"}""").field("id")
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$id", """{"name":""}""").status)
    }

    @Test
    fun `creating a name that exists opens that household instead of making another`() = withApp {
        val first = post("/households", """{"name":"Flat 4B"}""")
        assertEquals(HttpStatusCode.Created, first.status)
        val again = post("/households", """{"name":"  flat 4b "}""")
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(first.field("id"), again.field("id"))
    }

    @Test
    fun `renaming to a name another household has is refused`() = withApp {
        post("/households", """{"name":"Taken"}""")
        val id = post("/households", """{"name":"Mine"}""").field("id")
        val res = patch("/households/$id", """{"name":"taken"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("name_taken", res.field("code"))
        assertEquals(HttpStatusCode.OK, patch("/households/$id", """{"name":"MINE"}""").status)
    }
}
