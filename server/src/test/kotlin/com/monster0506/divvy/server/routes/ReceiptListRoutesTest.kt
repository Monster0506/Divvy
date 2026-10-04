package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReceiptListRoutesTest {
    private val repos = ExposedRepositories(TestDatabase.db.database)
    private val usd = CurrencyCode("USD")

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { receiptListRoutes({ repos.households }, { repos.receipts }) }
        }
        block()
    }

    private fun setup(): Pair<Household, List<Person>> = runBlocking {
        val home = repos.households.create("Home", usd)
        home to listOf(repos.people.add(home.id, "Sumedh"), repos.people.add(home.id, "Savar"))
    }

    private fun add(home: Household, payer: Person, merchant: String?, at: String, total: Long = 100) = runBlocking {
        repos.receipts.create(
            NewReceipt(home.id, payer.id, merchant, Instant.parse(at), usd, null, null, total, emptyList()),
        )
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.ids(): List<String> = getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `lists newest first with summary fields`() = withApp {
        val (home, people) = setup()
        val old = add(home, people[0], "Aldi", "2026-09-01T10:00:00Z", 1250)
        val new = add(home, people[1], "Costco", "2026-09-05T10:00:00Z")

        val res = client.get("/households/${home.id}/receipts")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertEquals(listOf(new.id.toString(), old.id.toString()), body.ids())
        val first = body.getValue("items").jsonArray[1].jsonObject
        assertEquals("Aldi", first.getValue("merchantName").jsonPrimitive.content)
        assertEquals("1250", first.getValue("totalMinor").jsonPrimitive.content)
        assertEquals("draft", first.getValue("status").jsonPrimitive.content)
        assertNull(body["nextCursor"])
    }

    @Test
    fun `cursor pages are stable and do not overlap, including equal purchase times`() = withApp {
        val (home, people) = setup()
        val created = (1..5).map { add(home, people[0], "Aldi", if (it <= 2) "2026-09-01T10:00:00Z" else "2026-09-0${it}T10:00:00Z") }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val suffix = cursor?.let { "&cursor=$it" } ?: ""
            val body = client.get("/households/${home.id}/receipts?limit=2$suffix").json()
            assertEquals(true, body.ids().size <= 2)
            seen += body.ids()
            cursor = body["nextCursor"]?.jsonPrimitive?.content
        } while (cursor != null)

        assertEquals(5, seen.size)
        assertEquals(created.map { it.id.toString() }.toSet(), seen.toSet())
        assertEquals(seen, client.get("/households/${home.id}/receipts").json().ids())
    }

    @Test
    fun `filters by status, merchant, date range and payer`() = withApp {
        val (home, people) = setup()
        val aldi = add(home, people[0], "Aldi", "2026-09-01T10:00:00Z")
        val costco = add(home, people[1], "Costco", "2026-09-10T10:00:00Z")
        runBlocking { repos.receipts.confirm(costco.id) }

        suspend fun ids(query: String) = client.get("/households/${home.id}/receipts?$query").json().ids()

        assertEquals(listOf(costco.id.toString()), ids("status=confirmed"))
        assertEquals(listOf(aldi.id.toString()), ids("merchant=ALDI"))
        assertEquals(listOf(costco.id.toString()), ids("from=2026-09-05"))
        assertEquals(listOf(aldi.id.toString()), ids("to=2026-09-05"))
        assertEquals(listOf(costco.id.toString()), ids("paidBy=${people[1].id}"))
        assertEquals(emptyList(), ids("status=void"))
    }

    @Test
    fun `only the households own receipts are listed`() = withApp {
        val (home, people) = setup()
        val (other, otherPeople) = setup()
        add(other, otherPeople[0], "Lidl", "2026-09-01T10:00:00Z")
        val mine = add(home, people[0], "Aldi", "2026-09-02T10:00:00Z")

        assertEquals(listOf(mine.id.toString()), client.get("/households/${home.id}/receipts").json().ids())
    }

    @Test
    fun `bad input returns 400 and unknown household returns 404`() = withApp {
        val (home, _) = setup()
        for (query in listOf("status=nope", "from=yesterday", "to=x", "paidBy=abc", "cursor=zzz", "limit=0", "limit=101", "limit=x")) {
            val res = client.get("/households/${home.id}/receipts?$query")
            assertEquals(HttpStatusCode.BadRequest, res.status, query)
            assertNotNull(res.json()["code"])
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/households/00000000-0000-0000-0000-000000000001/receipts").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/receipts").status)
    }

    @Test
    fun `a receipt still being read is listed with no payer instead of the text null`() = withApp {
        val (home, _) = setup()
        runBlocking { repos.receipts.createProcessing(home.id, "image-key") }

        val item = client.get("/households/${home.id}/receipts").json().getValue("items").jsonArray.single().jsonObject

        assertEquals("processing", item.getValue("status").jsonPrimitive.content)
        assertNull(item["paidByPersonId"])
    }

    @Test
    fun `receipts that are processing or failed can be filtered by status`() = withApp {
        val (home, _) = setup()
        val reading = runBlocking { repos.receipts.createProcessing(home.id, "a") }
        val broken = runBlocking { repos.receipts.createProcessing(home.id, "b") }
        runBlocking { repos.receipts.failProcessing(broken.id, "no items") }

        assertEquals(listOf(reading.id.toString()), client.get("/households/${home.id}/receipts?status=processing").json().ids())
        assertEquals(listOf(broken.id.toString()), client.get("/households/${home.id}/receipts?status=failed").json().ids())
    }
}
