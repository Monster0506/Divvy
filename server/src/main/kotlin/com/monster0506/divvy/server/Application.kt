package com.monster0506.divvy.server

import com.monster0506.divvy.server.db.DatabaseFactory
import com.monster0506.divvy.server.db.DivyDatabase
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val version: String)

/**
 * Entry point loaded by `io.ktor.server.netty.EngineMain` from `application.conf`.
 * Tests that have no database pass [connectDatabase] = false.
 */
fun Application.module(connectDatabase: Boolean = true) {
    val config = AppConfig.from(environment.config)

    install(ContentNegotiation) { json() }
    install(CallLogging)
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
