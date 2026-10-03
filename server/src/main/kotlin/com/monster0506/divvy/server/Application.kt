package com.monster0506.divvy.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

@Serializable
data class HealthResponse(val status: String, val version: String)

fun Application.module() {
    val config = AppConfig.from(environment.config)
    DatabaseFactory.init(config)
    configureApp(config)
}

fun Application.configureApp(config: AppConfig) {
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
    install(StatusPages) {
        exception<ValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("validation_error", cause.message ?: "Invalid request", cause.details),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.application.environment.log.warn("Bad request", cause)
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Malformed request"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", cause.message ?: "Not found"),
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", "Internal server error"),
            )
        }
    }
    routing {
        get("/health") { call.respond(HealthResponse("ok", config.version)) }
    }
}
