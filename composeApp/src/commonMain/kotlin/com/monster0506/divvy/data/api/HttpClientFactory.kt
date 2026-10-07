package com.monster0506.divvy.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
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
    explicitNulls = false
}

/**
 * Builds the shared client; the engine defaults to the one bundled for the current platform.
 *
 * [onConnectionProblem] is called whenever a request can't reach the server or gets a 401, so the
 * app-wide connection status can recheck instead of each screen explaining the problem itself.
 */
fun createHttpClient(
    config: ApiConfig = ApiConfig(),
    engine: HttpClientEngine? = null,
    onConnectionProblem: (() -> Unit)? = null,
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
    val client = if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
    client.requestPipeline.intercept(HttpRequestPipeline.Before) {
        // Objects are sent as JSON. Multipart and other ready-made bodies keep their own content type.
        if (context.contentType() == null && subject !is OutgoingContent) {
            context.contentType(ContentType.Application.Json)
        }
    }
    if (onConnectionProblem != null) {
        client.plugin(HttpSend).intercept { request ->
            val call = try {
                execute(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onConnectionProblem()
                throw e
            }
            if (call.response.status == HttpStatusCode.Unauthorized) onConnectionProblem()
            call
        }
    }
    return client
}
