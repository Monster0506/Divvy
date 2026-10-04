package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.ApiError
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
import kotlin.test.assertTrue

class HouseholdApiErrorsTest {
    private val unknown = "00000000-0000-0000-0000-000000000001"
    private val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(lenientJson) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            routing {
                householdRoutes { repos.households }
                personRoutes({ repos.households }, { repos.people })
                personArchiveRoutes({ repos.households }, { repos.people })
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String? = null): HttpResponse =
        client.post(path) {
            if (json != null) {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    /** The response must have the expected status and a body that decodes as an [ApiError]. */
    private suspend fun assertApiError(res: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, res.status)
        val body = res.bodyAsText()
        lenientJson.decodeFromString<ApiError>(body)
        assertTrue(body.contains("\"$code\""), "expected error code $code in $body")
    }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.newHousehold(): String =
        post("/households", """{"name":"Home"}""").field("id")

    private suspend fun ApplicationTestBuilder.addPerson(household: String, name: String): String =
        post("/households/$household/people", """{"displayName":"$name"}""").field("id")

    private suspend fun ApplicationTestBuilder.names(path: String): List<String> =
        Json.parseToJsonElement(client.get(path).bodyAsText())
            .jsonArray.map { it.jsonObject.getValue("displayName").jsonPrimitive.content }

    @Test
    fun `household errors use the ApiError format`() = withApp {
        assertApiError(post("/households", """{"name":""}"""), HttpStatusCode.BadRequest, "validation_error")
        assertApiError(post("/households", "not json"), HttpStatusCode.BadRequest, "validation_error")
        assertApiError(
            post("/households", """{"name":"Home","defaultCurrency":"dollars"}"""),
            HttpStatusCode.BadRequest,
            "validation_error",
        )
        assertApiError(client.get("/households/$unknown"), HttpStatusCode.NotFound, "not_found")
        assertApiError(patch("/households/$unknown", """{"name":"X"}"""), HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun `person errors use the ApiError format`() = withApp {
        val h = newHousehold()
        val samId = addPerson(h, "Sam")

        assertApiError(
            post("/households/$h/people", """{"displayName":"SAM"}"""),
            HttpStatusCode.BadRequest,
            "validation_error",
        )
        assertApiError(
            post("/households/$h/people", """{"displayName":"  "}"""),
            HttpStatusCode.BadRequest,
            "validation_error",
        )
        assertApiError(
            post("/households/$unknown/people", """{"displayName":"X"}"""),
            HttpStatusCode.NotFound,
            "not_found",
        )
        assertApiError(client.get("/households/$unknown/people"), HttpStatusCode.NotFound, "not_found")
        assertApiError(
            patch("/households/$h/people/$unknown", """{"displayName":"X"}"""),
            HttpStatusCode.NotFound,
            "not_found",
        )
        assertApiError(
            patch("/households/$h/people/$samId", """{"displayName":""}"""),
            HttpStatusCode.BadRequest,
            "validation_error",
        )
        assertApiError(post("/households/$h/people/$unknown/archive"), HttpStatusCode.NotFound, "not_found")
        assertApiError(post("/households/$unknown/people/$samId/archive"), HttpStatusCode.NotFound, "not_found")
    }

    @Test
    fun `archived people are hidden by default and shown with includeArchived`() = withApp {
        val h = newHousehold()
        val ids = listOf("Sumedh", "Savar", "Andrew").map { addPerson(h, it) }
        val archived = post("/households/$h/people/${ids[1]}/archive")
        assertEquals(HttpStatusCode.OK, archived.status)
        assertTrue(archived.bodyAsText().contains("archivedAt"))

        assertEquals(setOf("Sumedh", "Andrew"), names("/households/$h/people").toSet())
        assertEquals(
            setOf("Sumedh", "Savar", "Andrew"),
            names("/households/$h/people?includeArchived=true").toSet(),
        )
    }

    @Test
    fun `a name freed by archiving can be used again`() = withApp {
        val h = newHousehold()
        val id = addPerson(h, "Sam")
        post("/households/$h/people/$id/archive")
        assertEquals(
            HttpStatusCode.Created,
            post("/households/$h/people", """{"displayName":"sam"}""").status,
        )
    }
}