package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.ml.MemoryStorage
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.get
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

class HouseholdNameAsIdTest {
    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            install(HouseholdNameAsId) { households = { repos.households } }
            routing {
                householdRoutes { repos.households }
                personRoutes({ repos.households }, { repos.people }, { MemoryStorage() })
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `a name works wherever an id does`() = withApp {
        val id = post("/households", """{"name":"Flat 4B"}""").field("id")

        val byName = client.get("/households/Flat%204B")
        assertEquals(HttpStatusCode.OK, byName.status)
        assertEquals(id, byName.field("id"))

        assertEquals(HttpStatusCode.Created, post("/households/Flat%204B/people", """{"displayName":"Sam"}""").status)
        val people = Json.parseToJsonElement(client.get("/households/flat%204b/people").bodyAsText()).jsonArray
        assertEquals(listOf("Sam"), people.map { it.jsonObject.getValue("displayName").jsonPrimitive.content })
        assertEquals(1, Json.parseToJsonElement(client.get("/households/$id/people").bodyAsText()).jsonArray.size)
    }

    @Test
    fun `the query string survives the rewrite`() = withApp {
        post("/households", """{"name":"Home"}""")
        post("/households/Home/people", """{"displayName":"Ana"}""")
        val body = client.get("/households/Home/people?includeArchived=true").bodyAsText()
        assertEquals(1, Json.parseToJsonElement(body).jsonArray.size)
    }

    @Test
    fun `names with slashes and spaces are looked up whole`() = withApp {
        val id = post("/households", """{"name":"Mom & Dad's / Place"}""").field("id")
        val res = client.get("/households/Mom%20%26%20Dad's%20%2F%20Place")
        assertEquals(id, res.field("id"))
    }

    @Test
    fun `an unknown name is a plain 404 and ids still work`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/households/Nobody").status)
        val id = post("/households", """{"name":"Real"}""").field("id")
        assertEquals(HttpStatusCode.OK, client.get("/households/$id").status)
    }
}
