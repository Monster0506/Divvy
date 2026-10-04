package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotNull

class PersonArchiveRoutesTest {
    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { personArchiveRoutes({ repos.households }, { repos.people }) }
        }
        block()
    }

    private suspend fun HttpResponse.field(name: String): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject[name]?.jsonPrimitive?.content

    @Test
    fun `archive sets archivedAt and is idempotent`() = withApp {
        val home = repos.households.create("Home", CurrencyCode("USD"))
        val sam = repos.people.add(home.id, "Sam")

        val first = client.post("/households/${home.id}/people/${sam.id}/archive")
        assertEquals(HttpStatusCode.OK, first.status)
        val archivedAt = first.field("archivedAt")
        assertNotNull(archivedAt)

        val second = client.post("/households/${home.id}/people/${sam.id}/archive")
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(archivedAt, second.field("archivedAt"))
    }

    @Test
    fun `unknown household or person returns 404`() = withApp {
        val unknown = "00000000-0000-0000-0000-000000000001"
        val home = repos.households.create("Home", CurrencyCode("USD"))
        val other = repos.households.create("Other", CurrencyCode("USD"))
        val sam = repos.people.add(home.id, "Sam")

        assertEquals(HttpStatusCode.NotFound, client.post("/households/$unknown/people/${sam.id}/archive").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${home.id}/people/$unknown/archive").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${other.id}/people/${sam.id}/archive").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${home.id}/people/not-a-uuid/archive").status)
    }
}