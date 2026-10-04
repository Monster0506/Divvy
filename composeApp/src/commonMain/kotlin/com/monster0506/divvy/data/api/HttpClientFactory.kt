package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Where and how to reach the Divvy server. */
data class ApiConfig(
    val baseUrl: String = BuildApiConfig.BASE_URL,
    val apiKey: String = BuildApiConfig.API_KEY,
)

const val API_KEY_HEADER = "X-Api-Key"

internal val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Builds the shared client; the engine defaults to the one bundled for the current platform. */
fun createHttpClient(
    config: ApiConfig = ApiConfig(),
    engine: HttpClientEngine? = null,
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) { json(ApiJson) }
        install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        defaultRequest {
            url(config.baseUrl.trimEnd('/') + "/")
            header(API_KEY_HEADER, config.apiKey)
        }
    }
    return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
}
