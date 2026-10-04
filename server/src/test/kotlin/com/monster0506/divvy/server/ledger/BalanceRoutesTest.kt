package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.domain.NewSettlement
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class BalanceRoutesTest {
    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }
    private val usd = CurrencyCode("USD")

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { balanceRoutes({ repos.households }, { repos.people }, { repos.ledger }) }
        }
        block()
    }

    private class Home(val id: Uuid, val ann: Uuid, val ben: Uuid, val cy: Uuid)

    private fun home(currency: String = "USD"): Home = runBlocking {
        val id = repos.households.create("Home", CurrencyCode(currency)).id
        Home(id, repos.people.add(id, "Ann").id, repos.people.add(id, "Ben").id, repos.people.add(id, "Cy").id)
    }

    /** Writes a ledger entry like a confirmed receipt would and returns its id. */
    private suspend fun owe(h: Home, debtor: Uuid, creditor: Uuid, cents: Long, currency: CurrencyCode = usd): Uuid =
        repos.ledger.append(
            NewLedgerEntry(h.id, debtor, creditor, Money(cents, currency), LedgerSourceType.RECEIPT, Uuid.random()),
        ).id

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())

    private fun JsonObject.block(currency: String): JsonObject =
        getValue("balances").jsonArray.map { it.jsonObject }
            .first { it.getValue("currency").jsonPrimitive.content == currency }

    private fun JsonObject.person(id: Uuid): JsonObject =
        getValue("people").jsonArray.map { it.jsonObject }
            .first { it.getValue("personId").jsonPrimitive.content == id.toString() }

    private fun JsonObject.net() = getValue("netMinor").jsonPrimitive.long

    private fun JsonObject.debts(field: String): Map<String, Long> =
        getValue(field).jsonArray.associate {
            it.jsonObject.getValue("personId").jsonPrimitive.content to it.jsonObject.getValue("amountMinor").jsonPrimitive.long
        }

    @Test
    fun `balances show net and pairwise debts after receipts and a settlement`() = withApp {
        val h = home()
        owe(h, debtor = h.ben, creditor = h.ann, cents = 3000)
        owe(h, debtor = h.cy, creditor = h.ann, cents = 1000)
        owe(h, debtor = h.cy, creditor = h.ben, cents = 500)
        repos.settlements.record(NewSettlement(h.id, fromPersonId = h.ben, toPersonId = h.ann, amount = Money(1000, usd)))

        val res = client.get("/households/${h.id}/balances")

        assertEquals(HttpStatusCode.OK, res.status)
        val usdBlock = res.json().jsonObject.block("USD")
        assertEquals(3000L, usdBlock.person(h.ann).net())
        assertEquals(-1500L, usdBlock.person(h.ben).net())
        assertEquals(-1500L, usdBlock.person(h.cy).net())
        assertEquals(mapOf(h.ben.toString() to 2000L, h.cy.toString() to 1000L), usdBlock.person(h.ann).debts("owedBy"))
        assertEquals(mapOf(h.ann.toString() to 2000L), usdBlock.person(h.ben).debts("owes"))
        assertEquals(mapOf(h.cy.toString() to 500L), usdBlock.person(h.ben).debts("owedBy"))
        assertEquals(mapOf(h.ann.toString() to 1000L, h.ben.toString() to 500L), usdBlock.person(h.cy).debts("owes"))
    }

    @Test
    fun `net balances sum to zero`() = withApp {
        val h = home()
        owe(h, h.ben, h.ann, 3333)
        owe(h, h.cy, h.ann, 1)
        owe(h, h.ann, h.cy, 777)
        repos.settlements.record(NewSettlement(h.id, h.cy, h.ben, Money(42, usd)))

        val people = client.get("/households/${h.id}/balances").json().jsonObject.block("USD")
            .getValue("people").jsonArray.map { it.jsonObject }

        assertEquals(0L, people.sumOf { it.net() })
    }

    @Test
    fun `an empty household lists everyone at zero in its own currency`() = withApp {
        val h = home("EUR")

        val json = client.get("/households/${h.id}/balances").json().jsonObject

        assertEquals(1, json.getValue("balances").jsonArray.size)
        val people = json.block("EUR").getValue("people").jsonArray.map { it.jsonObject }
        assertEquals(3, people.size)
        assertTrue(people.all { it.net() == 0L })
        assertTrue(people.all { it.debts("owes").isEmpty() && it.debts("owedBy").isEmpty() })
    }

    @Test
    fun `currencies are reported separately`() = withApp {
        val h = home("USD")
        owe(h, h.ben, h.ann, 500, usd)
        owe(h, h.ben, h.ann, 900, CurrencyCode("EUR"))

        val json = client.get("/households/${h.id}/balances").json().jsonObject

        assertEquals(listOf("EUR", "USD"), json.getValue("balances").jsonArray.map { it.jsonObject.getValue("currency").jsonPrimitive.content })
        assertEquals(500L, json.block("USD").person(h.ann).net())
        assertEquals(900L, json.block("EUR").person(h.ann).net())
    }

    @Test
    fun `an archived person is listed only while they have a balance`() = withApp {
        val h = home()
        repos.people.archive(h.cy)

        fun ids(json: JsonObject) = json.block("USD").getValue("people").jsonArray
            .map { it.jsonObject.getValue("personId").jsonPrimitive.content }

        val before = client.get("/households/${h.id}/balances").json().jsonObject
        assertFalse(h.cy.toString() in ids(before))

        owe(h, debtor = h.cy, creditor = h.ann, cents = 400)

        val after = client.get("/households/${h.id}/balances").json().jsonObject
        assertTrue(h.cy.toString() in ids(after))
        assertEquals(-400L, after.block("USD").person(h.cy).net())
    }

    @Test
    fun `the ledger lists every entry oldest first with its source`() = withApp {
        val h = home()
        owe(h, h.ben, h.ann, 100)
        owe(h, h.cy, h.ann, 200)
        val settlement = repos.settlements.record(NewSettlement(h.id, h.ben, h.ann, Money(50, usd)))

        val res = client.get("/households/${h.id}/ledger")

        assertEquals(HttpStatusCode.OK, res.status)
        val entries: JsonArray = res.json().jsonArray
        assertEquals(listOf(100L, 200L, 50L), entries.map { it.jsonObject.getValue("amountMinor").jsonPrimitive.long })
        val last = entries.last().jsonObject
        assertEquals("settlement", last.getValue("sourceType").jsonPrimitive.content)
        assertEquals(settlement.id.toString(), last.getValue("sourceId").jsonPrimitive.content)
        assertEquals("receipt", entries.first().jsonObject.getValue("sourceType").jsonPrimitive.content)
        assertNull(entries.first().jsonObject["personEffectMinor"])
    }

    @Test
    fun `filtering by person lists their entries and the effects add up to their balance`() = withApp {
        val h = home()
        owe(h, h.ben, h.ann, 3000)
        owe(h, h.cy, h.ben, 500)
        owe(h, h.cy, h.ann, 999) // does not involve ben
        repos.settlements.record(NewSettlement(h.id, h.ben, h.ann, Money(1000, usd)))

        val entries = client.get("/households/${h.id}/ledger?personId=${h.ben}").json().jsonArray.map { it.jsonObject }

        assertEquals(3, entries.size)
        val effects = entries.sumOf { it.getValue("personEffectMinor").jsonPrimitive.long }
        val net = client.get("/households/${h.id}/balances").json().jsonObject.block("USD").person(h.ben).net()
        assertEquals(net, effects)
        assertEquals(-1500L, effects)
    }

    @Test
    fun `reversed entries stay in the ledger and point at what they cancel`() = withApp {
        val h = home()
        val original = owe(h, h.ben, h.ann, 700)
        val reversal = repos.ledger.reverse(original)!!

        val entries = client.get("/households/${h.id}/ledger?personId=${h.ann}").json().jsonArray.map { it.jsonObject }

        assertEquals(2, entries.size)
        val reversalJson = entries.first { it.getValue("id").jsonPrimitive.content == reversal.id.toString() }
        assertEquals(original.toString(), reversalJson.getValue("reversesEntryId").jsonPrimitive.content)
        assertEquals(0L, entries.sumOf { it.getValue("personEffectMinor").jsonPrimitive.long })
        val people = client.get("/households/${h.id}/balances").json().jsonObject.block("USD")
        assertEquals(0L, people.person(h.ann).net())
    }

    @Test
    fun `an invalid personId returns 400`() = withApp {
        val h = home()
        val other = home()
        assertEquals(HttpStatusCode.BadRequest, client.get("/households/${h.id}/ledger?personId=nope").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/households/${h.id}/ledger?personId=").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/households/${h.id}/ledger?personId=${Uuid.random()}").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/households/${h.id}/ledger?personId=${other.ann}").status)
    }

    @Test
    fun `unknown or malformed household returns 404`() = withApp {
        for (path in listOf("balances", "ledger")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/$path").status, path)
            assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/$path").status, path)
        }
    }

    @Test
    fun `balances and ledger only cover the requested household`() = withApp {
        val a = home()
        val b = home()
        owe(a, a.ben, a.ann, 100)
        owe(b, b.ben, b.ann, 999)

        val entries = client.get("/households/${a.id}/ledger").json().jsonArray
        assertEquals(1, entries.size)
        assertEquals(100L, client.get("/households/${a.id}/balances").json().jsonObject.block("USD").person(a.ann).net())
    }
}