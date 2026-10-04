package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ListItemRoutesTest {
    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }
    private val usd = CurrencyCode("USD")

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { listItemRoutes({ repos.households }, { repos.people }, { repos.listItems }) }
        }
        block()
    }

    private class Home(val id: Uuid, val savar: Uuid, val sumedh: Uuid)

    private fun home(): Home = runBlocking {
        val id = repos.households.create("Home", usd).id
        Home(id, repos.people.add(id, "Savar").id, repos.people.add(id, "Sumedh").id)
    }

    /** A confirmed receipt with one line per name, which is what autocomplete learns from. */
    private fun bought(h: Home, vararg names: String, confirm: Boolean = true) = runBlocking {
        val receipt = repos.receipts.create(
            NewReceipt(
                householdId = h.id,
                paidByPersonId = h.savar,
                merchantName = "Trader Joe's",
                purchasedAt = Instant.parse("2026-09-30T17:45:00Z"),
                currency = usd,
                subtotalMinor = 100L * names.size,
                taxMinor = 0,
                totalMinor = 100L * names.size,
                items = names.map { NewReceiptItem(it, 100, BigDecimal.ONE, 100) },
            ),
        )
        if (confirm) repos.receipts.confirm(receipt.id)
    }

    private fun json(vararg fields: Pair<String, Any?>): String = buildJsonObject {
        fields.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, value)
                null -> Unit
                else -> put(key, value.toString())
            }
        }
    }.toString()

    private suspend fun ApplicationTestBuilder.add(householdId: Any, body: String): HttpResponse =
        client.post("/households/$householdId/list") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.edit(householdId: Any, itemId: Any, body: String): HttpResponse =
        client.patch("/households/$householdId/list/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.listed(householdId: Any): List<JsonObject> =
        Json.parseToJsonElement(client.get("/households/$householdId/list").bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.suggest(householdId: Any, q: String, limit: Int? = null): List<String> {
        val res = client.get("/households/$householdId/list/autocomplete?q=$q" + (limit?.let { "&limit=$it" } ?: ""))
        assertEquals(HttpStatusCode.OK, res.status)
        return res.obj().getValue("suggestions").jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `adding an item returns 201 with who added it`() = withApp {
        val h = home()
        val res = add(h.id, json("text" to "  Eggs ", "quantity" to "1 dozen", "notes" to "Free range", "addedByPersonId" to h.sumedh))

        assertEquals(HttpStatusCode.Created, res.status)
        val item = res.obj()
        assertEquals("Eggs", item.str("text"))
        assertEquals("1 dozen", item.str("quantity"))
        assertEquals("Free range", item.str("notes"))
        assertEquals(h.sumedh.toString(), item.str("addedByPersonId"))
        assertEquals("Sumedh", item.str("addedByName"))
        assertEquals("open", item.str("status"))
        assertNotNull(item.str("createdAt"))
        assertNull(item.str("purchasedAt"))
    }

    @Test
    fun `only text is required`() = withApp {
        val h = home()
        val item = add(h.id, json("text" to "Milk", "quantity" to "  ")).obj()

        assertEquals("Milk", item.str("text"))
        assertNull(item.str("quantity"))
        assertNull(item.str("notes"))
        assertNull(item.str("addedByPersonId"))
    }

    @Test
    fun `an item can be read back on its own`() = withApp {
        val h = home()
        val id = add(h.id, json("text" to "Milk", "addedByPersonId" to h.savar)).obj().str("id")

        val res = client.get("/households/${h.id}/list/$id")

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Milk", res.obj().str("text"))
        assertEquals("Savar", res.obj().str("addedByName"))
    }

    @Test
    fun `invalid items return 400 and save nothing`() = withApp {
        val h = home()
        val other = home()
        val archived = runBlocking { repos.people.add(h.id, "Gone").also { repos.people.archive(it.id) }.id }
        val bad = listOf(
            "missing text" to json("quantity" to "2"),
            "blank text" to json("text" to "   "),
            "text too long" to json("text" to "x".repeat(MAX_LIST_ITEM_TEXT + 1)),
            "quantity too long" to json("text" to "Eggs", "quantity" to "x".repeat(MAX_LIST_ITEM_QUANTITY + 1)),
            "notes too long" to json("text" to "Eggs", "notes" to "x".repeat(MAX_LIST_ITEM_NOTES + 1)),
            "malformed person" to json("text" to "Eggs", "addedByPersonId" to "nope"),
            "unknown person" to json("text" to "Eggs", "addedByPersonId" to Uuid.random()),
            "person from another household" to json("text" to "Eggs", "addedByPersonId" to other.savar),
            "person who left" to json("text" to "Eggs", "addedByPersonId" to archived),
            "malformed json" to "{not json",
        )
        for ((label, body) in bad) {
            val res = add(h.id, body)
            assertEquals(HttpStatusCode.BadRequest, res.status, label)
            assertEquals("validation_error", res.obj().str("code"), label)
        }
        assertEquals(0, listed(h.id).size)
    }

    @Test
    fun `the list shows open items oldest first then purchased ones`() = withApp {
        val h = home()
        val eggs = add(h.id, json("text" to "Eggs")).obj().str("id")
        add(h.id, json("text" to "Milk"))
        add(h.id, json("text" to "Bread"))
        edit(h.id, eggs!!, json("purchased" to true))

        assertEquals(listOf("Milk", "Bread", "Eggs"), listed(h.id).map { it.str("text") })
    }

    @Test
    fun `the list only shows the household's own items`() = withApp {
        val a = home()
        val b = home()
        add(a.id, json("text" to "Eggs"))
        add(b.id, json("text" to "Milk"))

        assertEquals(listOf("Eggs"), listed(a.id).map { it.str("text") })
    }

    @Test
    fun `editing changes only the fields sent and an empty string clears one`() = withApp {
        val h = home()
        val id = add(h.id, json("text" to "Eggs", "quantity" to "6", "notes" to "Brown")).obj().str("id")!!

        val res = edit(h.id, id, json("text" to "Large eggs", "quantity" to ""))

        assertEquals(HttpStatusCode.OK, res.status)
        val item = res.obj()
        assertEquals("Large eggs", item.str("text"))
        assertNull(item.str("quantity"))
        assertEquals("Brown", item.str("notes"))
    }

    @Test
    fun `checking off an item records when and unchecking clears it`() = withApp {
        val h = home()
        val id = add(h.id, json("text" to "Eggs")).obj().str("id")!!

        val checked = edit(h.id, id, json("purchased" to true)).obj()
        assertEquals("purchased", checked.str("status"))
        val at = assertNotNull(checked.str("purchasedAt"))

        // Checking it off again keeps the first time.
        assertEquals(at, edit(h.id, id, json("purchased" to true)).obj().str("purchasedAt"))

        val unchecked = edit(h.id, id, json("purchased" to false)).obj()
        assertEquals("open", unchecked.str("status"))
        assertNull(unchecked.str("purchasedAt"))
    }

    @Test
    fun `invalid edits return 400 and change nothing`() = withApp {
        val h = home()
        val id = add(h.id, json("text" to "Eggs")).obj().str("id")!!
        for ((label, body) in listOf(
            "blank text" to json("text" to " "),
            "text too long" to json("text" to "x".repeat(MAX_LIST_ITEM_TEXT + 1)),
            "malformed json" to "{",
        )) {
            assertEquals(HttpStatusCode.BadRequest, edit(h.id, id, body).status, label)
        }
        assertEquals("Eggs", listed(h.id).single().str("text"))
    }

    @Test
    fun `removing an item returns 204 and it is gone`() = withApp {
        val h = home()
        val id = add(h.id, json("text" to "Eggs")).obj().str("id")
        add(h.id, json("text" to "Milk"))

        assertEquals(HttpStatusCode.NoContent, client.delete("/households/${h.id}/list/$id").status)

        assertEquals(listOf("Milk"), listed(h.id).map { it.str("text") })
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${h.id}/list/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/households/${h.id}/list/$id").status)
    }

    @Test
    fun `an item cannot be reached through another household`() = withApp {
        val a = home()
        val b = home()
        val id = add(a.id, json("text" to "Eggs")).obj().str("id")

        assertEquals(HttpStatusCode.NotFound, client.get("/households/${b.id}/list/$id").status)
        assertEquals(HttpStatusCode.NotFound, edit(b.id, id!!, json("text" to "Stolen")).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/households/${b.id}/list/$id").status)
        assertEquals(listOf("Eggs"), listed(a.id).map { it.str("text") })
    }

    @Test
    fun `unknown or malformed ids return 404`() = withApp {
        val h = home()
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/list").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/list").status)
        assertEquals(HttpStatusCode.NotFound, add(Uuid.random(), json("text" to "Eggs")).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${h.id}/list/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${h.id}/list/nope").status)
        assertEquals(HttpStatusCode.NotFound, edit(h.id, "nope", json("text" to "x")).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/list/autocomplete?q=a").status)
    }

    @Test
    fun `autocomplete suggests things the household bought, prefix matches and favourites first`() = withApp {
        val h = home()
        bought(h, "Large Eggs", "Eggplant", "Milk")
        bought(h, "Large Eggs", "Free range eggs")
        bought(h, "Large eggs")

        // "eggplant" starts with "egg"; the rest only contain it, and large eggs were bought most.
        assertEquals(listOf("Eggplant", "Large Eggs", "Free range eggs"), suggest(h.id, "egg"))
        assertEquals(listOf("Eggplant", "Large Eggs"), suggest(h.id, "egg", limit = 2))
    }

    @Test
    fun `autocomplete only uses this household's confirmed receipts`() = withApp {
        val h = home()
        val other = home()
        bought(h, "Oat milk")
        bought(h, "Milk chocolate", confirm = false)
        bought(other, "Whole milk")

        assertEquals(listOf("Oat milk"), suggest(h.id, "milk"))
    }

    @Test
    fun `autocomplete treats wildcards as plain text`() = withApp {
        val h = home()
        bought(h, "Bread", "50% cocoa")

        assertEquals(listOf("50% cocoa"), suggest(h.id, "50%25"))
        assertEquals(emptyList(), suggest(h.id, "_"))
    }

    @Test
    fun `autocomplete rejects a bad limit`() = withApp {
        val h = home()
        for (limit in listOf("0", "51", "x")) {
            val res = client.get("/households/${h.id}/list/autocomplete?q=a&limit=$limit")
            assertEquals(HttpStatusCode.BadRequest, res.status, limit)
        }
        assertEquals(emptyList(), suggest(h.id, ""))
    }
}
