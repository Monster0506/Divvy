package com.monster0506.divvy.server.ml

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 2_000L

/** Prediction can build a household's model the first time it is asked, which takes a few seconds. */
const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L

/** Reading a photo calls a vision model, which can take most of a minute. */
const val EXTRACT_TIMEOUT_MILLIS = 120_000L

private const val MAX_MESSAGE_LENGTH = 200

internal val mlJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Installs JSON and timeouts on an HTTP client used by [KtorMlClient]. */
fun HttpClientConfig<*>.configureMl(
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    // Non-2xx responses are mapped to results, not thrown.
    expectSuccess = false
    install(ContentNegotiation) { json(mlJson) }
    install(HttpTimeout) {
        this.connectTimeoutMillis = connectTimeoutMillis
        this.requestTimeoutMillis = requestTimeoutMillis
    }
}

/** Calls the ML service over HTTP. The ML service is internal to the Docker network and has no auth. */
class KtorMlClient(
    baseUrl: String,
    private val http: HttpClient,
) : MlClient, AutoCloseable {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun extract(imageBase64: String): MlResult<MlExtractedReceipt> = call {
        http.post {
            endpoint("receipts", "extract")
            json(MlExtractRequest(imageBase64))
            timeout { requestTimeoutMillis = EXTRACT_TIMEOUT_MILLIS }
        }
    }

    override suspend fun predict(request: MlPredictRequest): MlResult<MlPredictResponse> =
        call { http.post { endpoint("ownership", "predict"); json(request) } }

    override suspend fun upsertObservations(
        householdId: String,
        observations: List<MlObservation>,
        replace: Boolean,
    ): MlResult<MlUpsertResponse> = call {
        http.put {
            endpoint("ownership", "households", householdId, "observations")
            if (replace) url.parameters.append("replace", "true")
            json(MlUpsertRequest(observations))
        }
    }

    override suspend fun deleteObservation(householdId: String, observationId: String): MlResult<Unit> =
        callUnit { http.delete { endpoint("ownership", "households", householdId, "observations", observationId) } }

    override suspend fun history(householdId: String): MlResult<MlHistoryStatus> =
        call { http.get { endpoint("ownership", "households", householdId, "history") } }

    override fun close() = http.close()

    private fun HttpRequestBuilder.endpoint(vararg segments: String) {
        url(baseUrl)
        // a slash inside an id must stay part of that segment, not become a path separator
        url { appendPathSegments(*segments, encodeSlash = true) }
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> call(crossinline request: suspend () -> HttpResponse): MlResult<T> =
        guarded {
            val response = request()
            if (!response.status.isSuccess()) return@guarded failure(response)
            try {
                MlResult.Success(mlJson.decodeFromString<T>(response.bodyAsText()))
            } catch (e: kotlinx.serialization.SerializationException) {
                MlResult.Unavailable("unreadable response from the ML service: ${e.message?.take(MAX_MESSAGE_LENGTH)}")
            }
        }

    private suspend fun callUnit(request: suspend () -> HttpResponse): MlResult<Unit> =
        guarded {
            val response = request()
            if (response.status.isSuccess()) MlResult.Success(Unit) else failure(response)
        }

    /** Turns anything that goes wrong on the wire into [MlResult.Unavailable]. */
    private suspend fun <T> guarded(block: suspend () -> MlResult<T>): MlResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MlResult.Unavailable("${e::class.simpleName}: ${e.message?.take(MAX_MESSAGE_LENGTH)}")
        }

    private suspend fun failure(response: HttpResponse): MlResult<Nothing> {
        val status = response.status.value
        return if (status in 400..499) {
            MlResult.Rejected(status, errorMessage(response.bodyAsText()))
        } else {
            MlResult.Unavailable("ML service returned $status")
        }
    }

    companion object {
        /** A client for [baseUrl] with the production timeouts. */
        fun create(
            baseUrl: String,
            connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
            requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        ): KtorMlClient = KtorMlClient(
            baseUrl,
            HttpClient(CIO) { configureMl(connectTimeoutMillis, requestTimeoutMillis) },
        )
    }
}

/**
 * Pulls a readable message out of the ML service's error body: FastAPI sends `{"detail": "..."}`
 * for errors and `{"detail": [{"msg": "..."}]}` for validation failures.
 */
internal fun errorMessage(body: String): String {
    val detail = runCatching { mlJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull()
    val message = when (detail) {
        is JsonPrimitive -> detail.contentOrNull
        is JsonArray -> detail.firstOrNull()?.let { (it as? JsonObject)?.get("msg")?.let { m -> (m as? JsonPrimitive)?.contentOrNull } }
        else -> null
    }
    return (message ?: body).trim().ifEmpty { "no details" }.take(MAX_MESSAGE_LENGTH)
}
