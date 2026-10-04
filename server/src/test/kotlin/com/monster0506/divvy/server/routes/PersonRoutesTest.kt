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
            routing {
                householdRoutes { repos.households }
                personRoutes({ repos.households }, { repos.people })
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.newHousehold(): String =
        post("/households", """{"name":"Home"}""").field("id")

    private suspend fun ApplicationTestBuilder.names(householdId: String): List<String> =
        Json.parseToJsonElement(client.get("/households/$householdId/people").bodyAsText())
            .jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    @Test
    fun `a household with 4 people can be created and listed`() = withApp {
        val h = newHousehold()
        listOf("Sumedh", "Alex", "Sam", "Jo").forEach {
            assertEquals(HttpStatusCode.Created, post("/households/$h/people", """{"name":"$it"}""").status)
        }
        assertEquals(listOf("Sumedh", "Alex", "Sam", "Jo"), names(h))
    }

    @Test
    fun `duplicate name returns 400 case-insensitively`() = withApp {
        val h = newHousehold()
        post("/households/$h/people", """{"name":"Sumedh"}""")
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", """{"name":"sumedh"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", """{"name":"  SUMEDH "}""").status)
        assertEquals(1, names(h).size)
    }

    @Test
    fun `same name is allowed in a different household`() = withApp {
        val a = newHousehold()
        val b = newHousehold()
        assertEquals(HttpStatusCode.Created, post("/households/$a/people", """{"name":"Sam"}""").status)
        assertEquals(HttpStatusCode.Created, post("/households/$b/people", """{"name":"Sam"}""").status)
    }

    @Test
    fun `empty or too long names return 400`() = withApp {
        val h = newHousehold()
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", """{"name":""}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", """{"name":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", "not json").status)
        val tooLong = "a".repeat(81)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$h/people", """{"name":"$tooLong"}""").status)
    }

    @Test
    fun `patch renames a person`() = withApp {
        val h = newHousehold()
        val id = post("/households/$h/people", """{"name":"Sam"}""").field("id")
        val res = patch("/households/$h/people/$id", """{"name":"Samuel"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Samuel", res.field("name"))
        assertEquals(listOf("Samuel"), names(h))
    }

    @Test
    fun `patch to an existing name returns 400`() = withApp {
        val h = newHousehold()
        post("/households/$h/people", """{"name":"Alex"}""")
        val id = post("/households/$h/people", """{"name":"Sam"}""").field("id")
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$h/people/$id", """{"name":"ALEX"}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$h/people/$id", """{"name":""}""").status)
    }

    @Test
    fun `unknown household or person returns 404`() = withApp {
        val unknown = "00000000-0000-0000-0000-000000000001"
        val h = newHousehold()
        val other = newHousehold()
        val id = post("/households/$h/people", """{"name":"Sam"}""").field("id")
        assertEquals(HttpStatusCode.NotFound, client.get("/households/$unknown/people").status)
        assertEquals(HttpStatusCode.NotFound, post("/households/$unknown/people", """{"name":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$h/people/$unknown", """{"name":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$other/people/$id", """{"name":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$h/people/not-a-uuid", """{"name":"X"}""").status)
    }
}