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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonRoutesTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            routing { personRoutes({ repos.households }, { repos.people }) }
        }
        block()
    }

    private fun newHousehold(): String = runBlocking {
        ExposedRepositories(TestDatabase.db.database).households.create("Home", com.monster0506.divvy.server.domain.CurrencyCode("USD"))
    }.id.toString()

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    private suspend fun HttpResponse.names(): List<String> =
        Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject.getValue("displayName").jsonPrimitive.content }

    @Test
    fun `a household of four can be created and listed in order`() = withApp {
        val home = newHousehold()
        listOf("Sumedh", "Savar", "Andrew", "TJ").forEach {
            assertEquals(HttpStatusCode.Created, post("/households/$home/people", """{"displayName":"$it"}""").status)
        }
        assertEquals(listOf("Sumedh", "Savar", "Andrew", "TJ"), client.get("/households/$home/people").names())
    }

    @Test
    fun `create trims the name`() = withApp {
        val home = newHousehold()
        assertEquals("Andrew", post("/households/$home/people", """{"displayName":"  Andrew "}""").field("displayName"))
    }

    @Test
    fun `duplicate names are rejected case-insensitively`() = withApp {
        val home = newHousehold()
        post("/households/$home/people", """{"displayName":"Andrew"}""")
        val res = post("/households/$home/people", """{"displayName":"andrew"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("validation_error", res.field("code"))
    }

    @Test
    fun `the same name is allowed in different households`() = withApp {
        val a = newHousehold()
        val b = newHousehold()
        assertEquals(HttpStatusCode.Created, post("/households/$a/people", """{"displayName":"Andrew"}""").status)
        assertEquals(HttpStatusCode.Created, post("/households/$b/people", """{"displayName":"Andrew"}""").status)
    }

    @Test
    fun `invalid names and bodies return 400`() = withApp {
        val home = newHousehold()
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", """{"displayName":""}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", """{"displayName":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", "not json").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            post("/households/$home/people", """{"displayName":"${"a".repeat(81)}"}""").status,
        )
    }

    @Test
    fun `patch renames a person`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andy"}""").field("id")
        val res = patch("/households/$home/people/$id", """{"displayName":"Andrew"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Andrew", res.field("displayName"))
        assertEquals(listOf("Andrew"), client.get("/households/$home/people").names())
    }

    @Test
    fun `patch to an existing name returns 400 and invalid name returns 400`() = withApp {
        val home = newHousehold()
        post("/households/$home/people", """{"displayName":"Savar"}""")
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$home/people/$id", """{"displayName":"SAVAR"}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$home/people/$id", """{"displayName":""}""").status)
        assertEquals(HttpStatusCode.OK, patch("/households/$home/people/$id", """{"displayName":"ANDREW"}""").status)
    }

    @Test
    fun `unknown households and people return 404`() = withApp {
        val home = newHousehold()
        val other = newHousehold()
        val unknown = "00000000-0000-0000-0000-000000000001"
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        assertEquals(HttpStatusCode.NotFound, client.get("/households/$unknown/people").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/people").status)
        assertEquals(HttpStatusCode.NotFound, post("/households/$unknown/people", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$home/people/$unknown", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$home/people/nope", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$other/people/$id", """{"displayName":"X"}""").status)
    }
}
