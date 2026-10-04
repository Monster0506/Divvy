package com.monster0506.divvy.server.ml

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KtorMlClientExtractTest {
    private val seen = mutableListOf<HttpRequestData>()
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(
        requestTimeoutMillis: Long = 5_000,
        delayMillis: Long = 0,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
    ): KtorMlClient {
        val engine = MockEngine { request ->
            seen += request
            if (delayMillis > 0) delay(delayMillis)
            respond(body, status, json)
        }
        return KtorMlClient("http://ml:8000/", HttpClient(engine) { configureMl(2_000, requestTimeoutMillis) })
    }

    private val receiptBody = """
        {"store":"Walmart","purchased_on":"2026-09-18","items":[
          {"name":"BANANAS","normalized_name":"bananas","quantity":3.92,"unit_price_cents":48,"total_cents":188},
          {"name":"DONUTS","quantity":1,"total_cents":697}],
         "discounts":[],"subtotal_cents":885,"tax_cents":62,"total_cents":947,"warnings":["x"]}
    """.trimIndent()

    @Test
    fun `extract posts the image to the receipts endpoint and reads the receipt`() = runBlocking<Unit> {
        val ml = client(body = receiptBody)

        val result = assertIs<MlResult.Success<MlExtractedReceipt>>(ml.extract("aGVsbG8="))

        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://ml:8000/receipts/extract", request.url.toString())
        val sent = mlJson.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals("aGVsbG8=", sent.getValue("image_base64").jsonPrimitive.content)
        with(result.value) {
            assertEquals("Walmart", store)
            assertEquals("2026-09-18", purchasedOn)
            assertEquals(listOf("bananas", null), items.map { it.normalizedName })
            assertEquals(listOf(188L, 697L), items.map { it.totalCents })
            assertEquals(3.92, items[0].quantity)
            assertEquals(947L, totalCents)
        }
    }

    @Test
    fun `a refused photo is Rejected with the ML message`() = runBlocking<Unit> {
        val ml = client(
            status = HttpStatusCode.UnprocessableEntity,
            body = """{"detail":"the image is not a JPEG, PNG or WebP"}""",
        )

        val result = assertIs<MlResult.Rejected>(ml.extract("aGVsbG8="))

        assertEquals(422, result.status)
        assertEquals("the image is not a JPEG, PNG or WebP", result.message)
    }

    @Test
    fun `a failing vision service is Unavailable`() = runBlocking<Unit> {
        val ml = client(status = HttpStatusCode.BadGateway, body = """{"detail":"the vision service answered 500"}""")

        assertIs<MlResult.Unavailable>(ml.extract("aGVsbG8="))
    }

    @Test
    fun `reading a photo is allowed more time than the default request timeout`() = runBlocking<Unit> {
        val ml = client(requestTimeoutMillis = 50, delayMillis = 300, body = receiptBody)

        assertIs<MlResult.Success<MlExtractedReceipt>>(ml.extract("aGVsbG8="))
    }
}
