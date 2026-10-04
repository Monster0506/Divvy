package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettlementRoutesTest {
    private class Setup(val home: String, val ann: String, val ben: String, val outsider: String)

    private val repos get() = ExposedRepositories(TestDatabase.db.database)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val r = repos
            routing { settlementRoutes({ r.households }, { r.people }, { r.settlements }) }
        }
        block()
    }

    private fun setup(): Setup = runBlocking {
        val home = repos.households.create("Home", CurrencyCode("USD"))
        val other = repos.households.create("Other", CurrencyCode("USD"))
        Setup(
            home.id.toString(),
            repos.people.add(home.id, "Ann").id.toString(),
            repos.people.add(home.id, "Ben").id.toString(),
            repos.people.add(other.id, "Zed").id.toString(),
        )
    }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private fun body(from: String, to: String, amount: String, note: String? = null): String {
        val noteField = note?.let { ""","note":"$it"""" } ?: ""
        return """{"fromPersonId":"$from","toPersonId":"$to","amountMinor":$amount$noteField}"""
    }

    @Test
    fun `records a settlement in the household currency and lists it`() = withApp {
        val s = setup()
        val res = post("/households/${s.home}/settlements", body(s.ann, s.ben, "1250", " venmo "))
        assertEquals(HttpStatusCode.Created, res.status)
        val json = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("1250", json.getValue("amountMinor").jsonPrimitive.content)
        assertEquals("USD", json.getValue("currency").jsonPrimitive.content)
        assertEquals("venmo", json.getValue("note").jsonPrimitive.content)

        val list = Json.parseToJsonElement(client.get("/households/${s.home}/settlements").bodyAsText()).jsonArray
        assertEquals(1, list.size)
    }

    @Test
    fun `invalid requests return 400`() = withApp {
        val s = setup()
        val path = "/households/${s.home}/settlements"
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, s.ben, "0")).status)
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, s.ben, "-5")).status)
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, s.ann, "100")).status)
        assertEquals(HttpStatusCode.BadRequest, post(path, body("nope", s.ben, "100")).status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"fromPersonId":"${s.ann}","toPersonId":"${s.ben}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, "not json").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, s.ben, "100", "a".repeat(501))).status)
    }

    @Test
    fun `people from another household are rejected`() = withApp {
        val s = setup()
        val path = "/households/${s.home}/settlements"
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, s.outsider, "100")).status)
        assertEquals(HttpStatusCode.BadRequest, post(path, body(s.ann, "00000000-0000-0000-0000-000000000001", "100")).status)
    }

    @Test
    fun `unknown households return 404`() = withApp {
        val s = setup()
        val unknown = "00000000-0000-0000-0000-000000000001"
        assertEquals(HttpStatusCode.NotFound, post("/households/$unknown/settlements", body(s.ann, s.ben, "100")).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/$unknown/settlements").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/settlements").status)
    }
}
