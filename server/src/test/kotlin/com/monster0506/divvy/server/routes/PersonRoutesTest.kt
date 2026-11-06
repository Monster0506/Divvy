package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.ml.MemoryStorage
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonRoutesTest {
    private val storage = MemoryStorage()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)
    private val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(100)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            routing { personRoutes({ repos.households }, { repos.people }, { storage }) }
        }
        block()
    }

    private fun newHousehold(): String = runBlocking {
        ExposedRepositories(TestDatabase.db.database).households.create("Home", com.monster0506.divvy.server.domain.CurrencyCode("USD"))
    }.id.toString()

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    private suspend fun HttpResponse.names(): List<String> =
        Json.parseToJsonElement(bodyAsText()).jsonArray.map { it.jsonObject.getValue("displayName").jsonPrimitive.content }

    /** The field of a JSON object response, or null when it is absent. */
    private suspend fun HttpResponse.optionalField(name: String): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject[name]?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.uploadPhoto(path: String, bytes: ByteArray, part: String = "image"): HttpResponse =
        client.put(path) {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(part, bytes, Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"photo.bin\"")
                        })
                    },
                ),
            )
        }

    @Test
    fun `a household of four can be created and listed in order`() = withApp {
        val home = newHousehold()
        listOf("Sumedh", "Savar", "Andrew", "TJ").forEach {
            assertEquals(HttpStatusCode.Created, post("/households/$home/people", """{"displayName":"$it"}""").status)
        }
        assertEquals(listOf("Sumedh", "Savar", "Andrew", "TJ"), client.get("/households/$home/people").names())
    }

    @Test
    fun `create trims the name`() = withApp {
        val home = newHousehold()
        assertEquals("Andrew", post("/households/$home/people", """{"displayName":"  Andrew "}""").field("displayName"))
    }

    @Test
    fun `duplicate names are rejected case-insensitively`() = withApp {
        val home = newHousehold()
        post("/households/$home/people", """{"displayName":"Andrew"}""")
        val res = post("/households/$home/people", """{"displayName":"andrew"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("validation_error", res.field("code"))
    }

    @Test
    fun `the same name is allowed in different households`() = withApp {
        val a = newHousehold()
        val b = newHousehold()
        assertEquals(HttpStatusCode.Created, post("/households/$a/people", """{"displayName":"Andrew"}""").status)
        assertEquals(HttpStatusCode.Created, post("/households/$b/people", """{"displayName":"Andrew"}""").status)
    }

    @Test
    fun `invalid names and bodies return 400`() = withApp {
        val home = newHousehold()
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", """{"displayName":""}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", """{"displayName":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, post("/households/$home/people", "not json").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            post("/households/$home/people", """{"displayName":"${"a".repeat(81)}"}""").status,
        )
    }

    @Test
    fun `patch renames a person`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andy"}""").field("id")
        val res = patch("/households/$home/people/$id", """{"displayName":"Andrew"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Andrew", res.field("displayName"))
        assertEquals(listOf("Andrew"), client.get("/households/$home/people").names())
    }

    @Test
    fun `patch to an existing name returns 400 and invalid name returns 400`() = withApp {
        val home = newHousehold()
        post("/households/$home/people", """{"displayName":"Savar"}""")
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$home/people/$id", """{"displayName":"SAVAR"}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch("/households/$home/people/$id", """{"displayName":""}""").status)
        assertEquals(HttpStatusCode.OK, patch("/households/$home/people/$id", """{"displayName":"ANDREW"}""").status)
    }

    @Test
    fun `patch saves, shows and clears a venmo username`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val nullVenmo = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText())
            .jsonArray.single().jsonObject["venmoUsername"]
        assertEquals(null, nullVenmo)

        val saved = patch("/households/$home/people/$id", """{"venmoUsername":" @Andrew-Roddy_7 "}""")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals("Andrew-Roddy_7", saved.field("venmoUsername"))
        assertEquals("Andrew", saved.field("displayName"))
        val listed = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText()).jsonArray.single()
        assertEquals("Andrew-Roddy_7", listed.jsonObject.getValue("venmoUsername").jsonPrimitive.content)

        // Renaming leaves the username alone.
        assertEquals("Andrew-Roddy_7", patch("/households/$home/people/$id", """{"displayName":"Drew"}""").field("venmoUsername"))

        val cleared = patch("/households/$home/people/$id", """{"venmoUsername":""}""")
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(null, Json.parseToJsonElement(cleared.bodyAsText()).jsonObject["venmoUsername"])
    }

    @Test
    fun `real venmo usernames with capitals and hyphens are accepted`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Savar"}""").field("id")
        listOf("Savar-Shrestha", "savar-shrestha", "@Savar-Shrestha").forEach {
            val res = patch("/households/$home/people/$id", """{"venmoUsername":"$it"}""")
            assertEquals(HttpStatusCode.OK, res.status, it)
            assertEquals(it.removePrefix("@"), res.field("venmoUsername"))
        }
    }

    @Test
    fun `invalid venmo usernames return 400 and change nothing`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        patch("/households/$home/people/$id", """{"venmoUsername":"andrew-r"}""")
        listOf("abcd", "a".repeat(31), "andrew r", "andrew.r", "andrew!", "@@andrew").forEach { bad ->
            val res = patch("/households/$home/people/$id", """{"venmoUsername":"$bad"}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, bad)
            assertEquals("validation_error", res.field("code"))
        }
        // A bad username fails the whole request, so the name is not changed either.
        assertEquals(
            HttpStatusCode.BadRequest,
            patch("/households/$home/people/$id", """{"displayName":"Drew","venmoUsername":"bad!"}""").status,
        )
        val listed = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText()).jsonArray.single().jsonObject
        assertEquals("Andrew", listed.getValue("displayName").jsonPrimitive.content)
        assertEquals("andrew-r", listed.getValue("venmoUsername").jsonPrimitive.content)
    }

    @Test
    fun `patch with no fields returns 400`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val res = patch("/households/$home/people/$id", "{}")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("send at least one field to change", res.field("message"))
    }

    @Test
    fun `patch sets, lists and clears short name, emoji and color`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val fresh = client.get("/households/$home/people")
        val listedFresh = Json.parseToJsonElement(fresh.bodyAsText()).jsonArray.single().jsonObject
        listOf("shortName", "emoji", "color", "photoUpdatedAt").forEach { assertNull(listedFresh[it], it) }

        val saved = patch("/households/$home/people/$id", """{"shortName":" AR ","emoji":"🍕","color":"teal"}""")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals("AR", saved.field("shortName"))
        assertEquals("🍕", saved.field("emoji"))
        assertEquals("teal", saved.field("color"))
        assertEquals("Andrew", saved.field("displayName"))

        val listed = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText()).jsonArray.single().jsonObject
        assertEquals("AR", listed.getValue("shortName").jsonPrimitive.content)
        assertEquals("🍕", listed.getValue("emoji").jsonPrimitive.content)
        assertEquals("teal", listed.getValue("color").jsonPrimitive.content)

        // Omitted fields stay as they are.
        val renamed = patch("/households/$home/people/$id", """{"displayName":"Drew"}""")
        assertEquals("AR", renamed.field("shortName"))
        assertEquals("🍕", renamed.field("emoji"))
        assertEquals("teal", renamed.field("color"))

        val noShortName = patch("/households/$home/people/$id", """{"shortName":""}""")
        assertEquals(HttpStatusCode.OK, noShortName.status)
        assertNull(noShortName.optionalField("shortName"))
        assertEquals("🍕", noShortName.field("emoji"))

        val noEmoji = patch("/households/$home/people/$id", """{"emoji":""}""")
        assertEquals(HttpStatusCode.OK, noEmoji.status)
        assertNull(noEmoji.optionalField("emoji"))
        assertEquals("teal", noEmoji.field("color"))

        val noColor = patch("/households/$home/people/$id", """{"color":""}""")
        assertEquals(HttpStatusCode.OK, noColor.status)
        assertNull(noColor.optionalField("color"))
    }

    @Test
    fun `every avatar color and boundary lengths are accepted`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        listOf("gray", "green", "teal", "blue", "purple", "pink", "orange", "yellow").forEach {
            assertEquals(it, patch("/households/$home/people/$id", """{"color":"$it"}""").field("color"))
        }
        assertEquals("A", patch("/households/$home/people/$id", """{"shortName":"A"}""").field("shortName"))
        val longEmoji = "👨‍👩‍👧‍👦"
        assertTrue(longEmoji.length <= 16)
        assertEquals(longEmoji, patch("/households/$home/people/$id", """{"emoji":"$longEmoji"}""").field("emoji"))
        val sixteen = "x".repeat(16)
        assertEquals(sixteen, patch("/households/$home/people/$id", """{"emoji":"$sixteen"}""").field("emoji"))
    }

    @Test
    fun `invalid short names, emoji and colors return 400 and change nothing`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        patch("/households/$home/people/$id", """{"shortName":"AR","emoji":"🍕","color":"teal"}""")

        listOf("ABC", "   ").forEach { bad ->
            val res = patch("/households/$home/people/$id", """{"shortName":"$bad"}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, bad)
            assertEquals("validation_error", res.field("code"))
            assertEquals("shortName must be 1 or 2 characters", res.field("message"))
        }
        listOf("   ", "x".repeat(17)).forEach { bad ->
            val res = patch("/households/$home/people/$id", """{"emoji":"$bad"}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, bad)
            assertEquals("validation_error", res.field("code"))
            assertEquals("emoji must be 1 to 16 characters", res.field("message"))
        }
        listOf("red", "Teal", " teal").forEach { bad ->
            val res = patch("/households/$home/people/$id", """{"color":"$bad"}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, bad)
            assertEquals("validation_error", res.field("code"))
            assertEquals("color must be one of gray, green, teal, blue, purple, pink, orange, yellow", res.field("message"))
        }
        // A bad field fails the whole request.
        assertEquals(
            HttpStatusCode.BadRequest,
            patch("/households/$home/people/$id", """{"displayName":"Drew","color":"red"}""").status,
        )

        val listed = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText()).jsonArray.single().jsonObject
        assertEquals("Andrew", listed.getValue("displayName").jsonPrimitive.content)
        assertEquals("AR", listed.getValue("shortName").jsonPrimitive.content)
        assertEquals("🍕", listed.getValue("emoji").jsonPrimitive.content)
        assertEquals("teal", listed.getValue("color").jsonPrimitive.content)
    }

    @Test
    fun `a photo can be uploaded, listed and downloaded`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")

        val uploaded = uploadPhoto("/households/$home/people/$id/photo", jpeg)
        assertEquals(HttpStatusCode.OK, uploaded.status)
        assertEquals(id, uploaded.field("id"))
        val stamp = assertNotNull(uploaded.optionalField("photoUpdatedAt"))
        assertEquals(1, storage.files.size)

        val listed = Json.parseToJsonElement(client.get("/households/$home/people").bodyAsText()).jsonArray.single().jsonObject
        assertEquals(stamp, listed.getValue("photoUpdatedAt").jsonPrimitive.content)

        val photo = client.get("/households/$home/people/$id/photo")
        assertEquals(HttpStatusCode.OK, photo.status)
        assertEquals(ContentType.Image.JPEG, photo.contentType()?.withoutParameters())
        assertContentEquals(jpeg, photo.bodyAsBytes())
    }

    @Test
    fun `replacing a photo deletes the old file and moves the timestamp`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val first = uploadPhoto("/households/$home/people/$id/photo", jpeg).field("photoUpdatedAt")
        val oldKey = storage.files.keys.single()

        val second = uploadPhoto("/households/$home/people/$id/photo", png)
        assertEquals(HttpStatusCode.OK, second.status)
        assertNotEquals(first, second.field("photoUpdatedAt"))
        assertEquals(1, storage.files.size)
        assertTrue(!storage.files.containsKey(oldKey))
        assertContentEquals(png, storage.files.values.single())

        val photo = client.get("/households/$home/people/$id/photo")
        assertEquals(ContentType.Image.PNG, photo.contentType()?.withoutParameters())
        assertContentEquals(png, photo.bodyAsBytes())
    }

    @Test
    fun `deleting a photo clears it, removes the file and can be repeated`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        uploadPhoto("/households/$home/people/$id/photo", jpeg)

        val deleted = client.delete("/households/$home/people/$id/photo")
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertNull(deleted.optionalField("photoUpdatedAt"))
        assertEquals("Andrew", deleted.field("displayName"))
        assertTrue(storage.files.isEmpty())

        val missing = client.get("/households/$home/people/$id/photo")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("person photo not found", missing.field("message"))

        val again = client.delete("/households/$home/people/$id/photo")
        assertEquals(HttpStatusCode.OK, again.status)
        assertNull(again.optionalField("photoUpdatedAt"))
    }

    @Test
    fun `an oversize photo returns 413 and nothing is stored`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val res = uploadPhoto("/households/$home/people/$id/photo", jpeg + ByteArray(2 * 1024 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertEquals("payload_too_large", res.field("code"))
        assertEquals("photo must be at most 2097152 bytes", res.field("message"))
        assertTrue(storage.files.isEmpty())

        val exact = jpeg + ByteArray(2 * 1024 * 1024 - jpeg.size)
        assertEquals(HttpStatusCode.OK, uploadPhoto("/households/$home/people/$id/photo", exact).status)
    }

    @Test
    fun `photos that are not JPEG or PNG return 415`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        listOf(heic, "just some text".toByteArray()).forEach { bytes ->
            val res = uploadPhoto("/households/$home/people/$id/photo", bytes)
            assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
            assertEquals("unsupported_media_type", res.field("code"))
            assertEquals("photo must be JPEG or PNG", res.field("message"))
        }
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `a photo upload without an image part returns 400`() = withApp {
        val home = newHousehold()
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val wrongPart = uploadPhoto("/households/$home/people/$id/photo", jpeg, part = "file")
        assertEquals(HttpStatusCode.BadRequest, wrongPart.status)
        assertEquals("validation_error", wrongPart.field("code"))

        val notMultipart = client.put("/households/$home/people/$id/photo") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, notMultipart.status)
        assertEquals("validation_error", notMultipart.field("code"))
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `photo endpoints return 404 for unknown households and people`() = withApp {
        val home = newHousehold()
        val other = newHousehold()
        val unknown = "00000000-0000-0000-0000-000000000001"
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        val cases = listOf(
            "/households/$unknown/people/$id/photo" to "household not found",
            "/households/$home/people/$unknown/photo" to "person not found",
            "/households/$home/people/nope/photo" to "person not found",
            "/households/$other/people/$id/photo" to "person not found",
        )
        for ((path, message) in cases) {
            for (res in listOf(uploadPhoto(path, jpeg), client.get(path), client.delete(path))) {
                assertEquals(HttpStatusCode.NotFound, res.status, path)
                assertEquals("not_found", res.field("code"))
                assertEquals(message, res.field("message"))
            }
        }
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `unknown households and people return 404`() = withApp {
        val home = newHousehold()
        val other = newHousehold()
        val unknown = "00000000-0000-0000-0000-000000000001"
        val id = post("/households/$home/people", """{"displayName":"Andrew"}""").field("id")
        assertEquals(HttpStatusCode.NotFound, client.get("/households/$unknown/people").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/people").status)
        assertEquals(HttpStatusCode.NotFound, post("/households/$unknown/people", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$home/people/$unknown", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$home/people/nope", """{"displayName":"X"}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/households/$other/people/$id", """{"displayName":"X"}""").status)
    }
}
