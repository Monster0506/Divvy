package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ExposedRepositories
import com.monster0506.divvy.server.storage.LocalFileImageStorage
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ReceiptRoutesTest {
    private val maxBytes = 1024L
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)
    private val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(100)

    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }
    private val storage = LocalFileImageStorage(Files.createTempDirectory("divvy-receipts"))

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val uploaded = java.util.concurrent.CopyOnWriteArrayList<Uuid>()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing {
                receiptRoutes({ repos.households }, { repos.receipts }, { storage }, maxBytes, onUploaded = { uploaded += it })
            }
        }
        block()
    }

    private fun createHousehold(currency: String = "USD"): Uuid =
        runBlocking { repos.households.create("Home", CurrencyCode(currency)).id }

    private suspend fun ApplicationTestBuilder.upload(householdId: String, bytes: ByteArray, part: String = "image"): HttpResponse =
        client.post("/households/$householdId/receipts") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(part, bytes, Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"r.bin\"")
                        })
                    },
                ),
            )
        }

    private suspend fun HttpResponse.field(name: String) =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun `a good upload tells the extraction hook, a rejected one does not`() = withApp {
        val household = createHousehold()

        val ok = upload(household.toString(), jpeg)
        upload(household.toString(), "nope".toByteArray())

        assertEquals(listOf(Uuid.parse(ok.field("id"))), uploaded.toList())
    }

    @Test
    fun `valid uploads return 201 with a processing receipt`() = withApp {
        val household = createHousehold("EUR")
        for (bytes in listOf(jpeg, png, heic)) {
            val res = upload(household.toString(), bytes)
            assertEquals(HttpStatusCode.Created, res.status)
            assertEquals("processing", res.field("status"))
        }
        val receipts = repos.receipts.listByHousehold(household)
        assertEquals(3, receipts.size)
        assertEquals(CurrencyCode("EUR"), receipts.first().currency)
    }

    @Test
    fun `the image is stored and linked to the receipt`() = withApp {
        val household = createHousehold()
        val id = Uuid.parse(upload(household.toString(), jpeg).field("id"))
        val receipt = assertNotNull(repos.receipts.findById(id))
        assertEquals(ReceiptStatus.PROCESSING, receipt.status)
        assertNull(receipt.paidByPersonId)
        assertContentEquals(jpeg, storage.get(assertNotNull(receipt.imageKey)))
    }

    @Test
    fun `a file that is not an image returns 415`() = withApp {
        val res = upload(createHousehold().toString(), "just some text".toByteArray())
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
        assertEquals("unsupported_media_type", res.field("code"))
    }

    @Test
    fun `an oversize image returns 413 and nothing is created`() = withApp {
        val household = createHousehold()
        val res = upload(household.toString(), jpeg + ByteArray(maxBytes.toInt()))
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertEquals("payload_too_large", res.field("code"))
        assertEquals(0, repos.receipts.listByHousehold(household).size)
    }

    @Test
    fun `an image of exactly the limit is accepted`() = withApp {
        val res = upload(createHousehold().toString(), jpeg + ByteArray(maxBytes.toInt() - jpeg.size))
        assertEquals(HttpStatusCode.Created, res.status)
    }

    @Test
    fun `a missing image part returns 400`() = withApp {
        val res = upload(createHousehold().toString(), jpeg, part = "photo")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `a non multipart body returns 400`() = withApp {
        val res = client.post("/households/${createHousehold()}/receipts") { setBody("hello") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `unknown or malformed household returns 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, upload(Uuid.random().toString(), jpeg).status)
        assertEquals(HttpStatusCode.NotFound, upload("nope", jpeg).status)
    }
}
