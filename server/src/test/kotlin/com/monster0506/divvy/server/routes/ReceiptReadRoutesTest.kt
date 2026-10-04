package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.repository.ExposedRepositories
import com.monster0506.divvy.server.storage.LocalFileImageStorage
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ReceiptReadRoutesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20)

    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }
    private val storage = LocalFileImageStorage(Files.createTempDirectory("divvy-receipt-read"))

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { receiptReadRoutes({ repos.receipts }, { storage }) }
        }
        block()
    }

    private fun uploadedReceipt(image: ByteArray?): Uuid = runBlocking {
        val household = repos.households.create("Home", CurrencyCode("USD"))
        repos.receipts.createProcessing(household.id, image?.let { storage.put(it) } ?: "missing-key").id
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `returns the receipt with its status`() = withApp {
        val id = uploadedReceipt(png)
        val res = client.get("/receipts/$id")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertEquals(id.toString(), body.getValue("id").jsonPrimitive.content)
        assertEquals("processing", body.getValue("status").jsonPrimitive.content)
        assertEquals(0, body.getValue("items").jsonArray.size)
    }

    @Test
    fun `unknown or malformed receipt ids return 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/receipts/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/receipts/nope").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/receipts/nope/image").status)
    }

    @Test
    fun `streams the image with its content type`() = withApp {
        val res = client.get("/receipts/${uploadedReceipt(png)}/image")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(ContentType.Image.PNG, ContentType.parse(res.headers[HttpHeaders.ContentType]!!))
        assertContentEquals(png, res.bodyAsBytes())
    }

    @Test
    fun `a receipt whose image is gone returns 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/receipts/${uploadedReceipt(null)}/image").status)
    }
}
