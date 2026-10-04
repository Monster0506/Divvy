package com.monster0506.divvy.server

import com.monster0506.divvy.server.db.DatabaseFactory
import com.monster0506.divvy.server.db.DivyDatabase
import com.monster0506.divvy.server.repository.ExposedRepositories
import com.monster0506.divvy.server.routes.householdRoutes
import com.monster0506.divvy.server.routes.receiptRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.MessageDigest
import io.ktor.server.application.application

@Serializable
data class HealthResponse(val status: String, val version: String)

/**
 * Entry point loaded by `io.ktor.server.netty.EngineMain` from `application.conf`.
 * Tests that have no database pass [connectDatabase] = false.
 */
fun Application.module(connectDatabase: Boolean = true) {
    val config = AppConfig.from(environment.config)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        })
    }
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} " +
                    "${call.response.status()?.value} ${call.processingTimeMillis()}ms"
        }
    }
    install(Authentication) {
        bearer(API_KEY_AUTH) {
            authHeader { call ->
                call.request.header(API_KEY_HEADER)?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { credential ->
                val expectedKey = config.apiKey
                if (expectedKey != null && keysMatch(credential.token, expectedKey)) {
                    UserIdPrincipal("api-key")
                } else {
                    null
                }
            }
        }
    }
    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("validation_error", cause.message ?: "Invalid request", cause.details),
            )
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", cause.message ?: "Not found"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Internal server error"))
        }
    }

    routing {
        get("/health") { call.respond(HealthResponse("ok", config.version)) }
        authenticate(API_KEY_AUTH) {
            householdRoutes { repositories.households }
            receiptRoutes({ repositories.receipts }, { repositories.people })
        }
    }

    if (connectDatabase) configureDatabase(config)
}

private val RepositoriesKey = AttributeKey<ExposedRepositories>("repositories")

/** The repositories routes use for data access. Available once [configureDatabase] has run. */
val Application.repositories: ExposedRepositories get() = attributes[RepositoriesKey]

/**
 * Connects to PostgreSQL and applies pending Flyway migrations before the server accepts
 * requests, then registers [repositories]. Startup fails if the database is unreachable or a
 * migration fails.
 */
fun Application.configureDatabase(config: AppConfig): DivyDatabase {
    val database = DatabaseFactory.connect(config)
    monitor.subscribe(ApplicationStopped) { database.close() }
    attributes.put(RepositoriesKey, ExposedRepositories(database.database))
    return database
}

const val API_KEY_AUTH = "api-key"
const val API_KEY_HEADER = "X-Api-Key"

private fun keysMatch(provided: String, expected: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    return MessageDigest.isEqual(
        digest.digest(provided.toByteArray()),
        digest.digest(expected.toByteArray()),
    )
}