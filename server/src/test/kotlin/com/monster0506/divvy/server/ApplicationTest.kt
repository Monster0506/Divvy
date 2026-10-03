package com.monster0506.divvy.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import com.typesafe.config.ConfigFactory
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {
    private val testSettings = mapOf(
        "divvy.version" to "1.2.3",
        "divvy.database.url" to "jdbc:postgresql://localhost/divvy",
        "divvy.database.user" to "divvy",
        "divvy.database.password" to "secret",
        "divvy.ml.url" to "http://localhost:8000",
        "divvy.apiKey" to "key",
    )

    private fun ApplicationTestBuilder.testConfig(settings: Map<String, String> = testSettings) {
        environment { config = MapApplicationConfig(*settings.toList().toTypedArray()) }
    }

    private fun Application.testModule() {
        configureApp(AppConfig.from(environment.config))
        routing {
            get("/boom") { error("secret internal detail") }
            get("/invalid") { throw ValidationException("Name is required", listOf("name")) }
            get("/missing") { throw NotFoundException("Household not found") }
        }
    }

    @Test
    fun moduleStarts() = testApplication {
        testConfig()
        application { configureApp(AppConfig.from(environment.config)) }
        startApplication()
    }

    @Test
    fun unexpectedExceptionReturns500WithoutLeakingDetails() = testApplication {
        testConfig()
        application { testModule() }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("secret internal detail"))
        assertEquals("internal_error", Json.decodeFromString<ApiError>(body).code)
    }

    @Test
    fun validationExceptionReturns400() = testApplication {
        testConfig()
        application { testModule() }
        val response = client.get("/invalid")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = Json.decodeFromString<ApiError>(response.bodyAsText())
        assertEquals("Name is required", error.message)
        assertEquals(listOf("name"), error.details)
    }

    @Test
    fun notFoundExceptionReturns404() = testApplication {
        testConfig()
        application { testModule() }
        val response = client.get("/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not_found"))
    }

    @Test
    fun healthReturnsOkWithVersion() = testApplication {
        testConfig()
        application { configureApp(AppConfig.from(environment.config)) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            HealthResponse("ok", "1.2.3"),
            Json.decodeFromString<HealthResponse>(response.bodyAsText()),
        )
    }

    @Test
    fun startupFailsClearlyWhenRequiredSettingMissing() {
        val config = MapApplicationConfig(*(testSettings - "divvy.apiKey" - "divvy.ml.url").toList().toTypedArray())
        val error = assertFailsWith<IllegalStateException> { AppConfig.from(config) }
        assertTrue(error.message!!.contains("API_KEY"))
        assertTrue(error.message!!.contains("ML_SERVICE_URL"))
    }

    @Test
    fun environmentVariablesMapToConfig() {
        val env = ConfigFactory.parseMap(
            mapOf(
                "PORT" to "9090",
                "DATABASE_URL" to "jdbc:postgresql://db/divvy",
                "DATABASE_USER" to "u",
                "DATABASE_PASSWORD" to "p",
                "ML_SERVICE_URL" to "http://ml:8000",
                "API_KEY" to "k",
            ),
        )
        val resolved = ConfigFactory.parseResources("application.conf").withFallback(env).resolve()
        val config = HoconApplicationConfig(resolved)
        assertEquals("9090", config.property("ktor.deployment.port").getString())
        val app = AppConfig.from(config)
        assertEquals("http://ml:8000", app.mlServiceUrl)
        assertEquals("k", app.apiKey)
        assertEquals("1.0", app.version)
    }
}
