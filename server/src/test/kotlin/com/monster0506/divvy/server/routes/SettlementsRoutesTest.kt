package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.LedgerSourceType
import com.monster0506.divvy.server.domain.Money
import com.monster0506.divvy.server.domain.NewLedgerEntry
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.get
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SettlementRoutesTest {
    private val repos by lazy { ExposedRepositories(TestDatabase.db.database) }

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { settlementRoutes({ repos.households }, { repos.people }, { repos.settlements }) }
        }
        block()
    }

    private class Home(val id: Uuid, val savar: Uuid, val sumedh: Uuid)

    private fun home(currency: String = "USD"): Home = runBlocking {
        val id = repos.households.create("Home", CurrencyCode(currency)).id
        Home(id, repos.people.add(id, "Savar").id, repos.people.add(id, "Sumedh").id)
    }

    private fun body(from: Any?, to: Any?, amount: Any?, note: String? = null): String = buildJsonObject {
        from?.let { put("fromPersonId", it.toString()) }
        to?.let { put("toPersonId", it.toString()) }
        when (amount) {
            is Long -> put("amountMinor", amount)
            is String -> put("amountMinor", amount)
        }
        note?.let { put("note", it) }
    }.toString()

    private suspend fun ApplicationTestBuilder.post(householdId: Any, json: String): HttpResponse =
        client.post("/households/$householdId/settlements") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun HttpResponse.obj() = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** What each person is owed (positive) or owes (negative) according to the ledger. */
    private suspend fun net(householdId: Uuid): Map<Uuid, Long> {
        val result = mutableMapOf<Uuid, Long>()
        repos.ledger.listByHousehold(householdId).forEach {
            result.merge(it.creditorPersonId, it.amount.amountMinor, Long::plus)
            result.merge(it.debtorPersonId, -it.amount.amountMinor, Long::plus)
        }
        return result
    }

    private suspend fun owe(h: Home, debtor: Uuid, creditor: Uuid, cents: Long) {
        repos.ledger.append(
            NewLedgerEntry(h.id, debtor, creditor, Money(cents, CurrencyCode("USD")), LedgerSourceType.RECEIPT, Uuid.random()),
        )
    }

    @Test
    fun `recording a settlement returns 201 and writes a reversing ledger entry`() = withApp {
        val h = home("EUR")
        val res = post(h.id, body(h.sumedh, h.savar, 2500L, note = "Venmo"))

        assertEquals(HttpStatusCode.Created, res.status)
        val json = res.obj()
        assertEquals(h.sumedh.toString(), json.getValue("fromPersonId").jsonPrimitive.content)
        assertEquals(h.savar.toString(), json.getValue("toPersonId").jsonPrimitive.content)
        assertEquals(2500L, json.getValue("amountMinor").jsonPrimitive.long)
        assertEquals("EUR", json.getValue("currency").jsonPrimitive.content)
        assertEquals("Venmo", json.getValue("note").jsonPrimitive.content)

        val settlementId = Uuid.parse(json.getValue("id").jsonPrimitive.content)
        val entry = repos.ledger.listBySource(LedgerSourceType.SETTLEMENT, settlementId).single()
        assertEquals(h.savar, entry.debtorPersonId) // the receiver is now "owed back" in reverse
        assertEquals(h.sumedh, entry.creditorPersonId)
        assertEquals(2500L, entry.amount.amountMinor)
    }

    @Test
    fun `a settlement cancels the debt it pays`() = withApp {
        val h = home()
        owe(h, debtor = h.sumedh, creditor = h.savar, cents = 2500)

        assertEquals(HttpStatusCode.Created, post(h.id, body(h.sumedh, h.savar, 2500L)).status)

        assertTrue(net(h.id).values.all { it == 0L }, "balances were ${net(h.id)}")
    }

    @Test
    fun `a partial settlement leaves the remainder owed`() = withApp {
        val h = home()
        owe(h, debtor = h.sumedh, creditor = h.savar, cents = 2500)

        post(h.id, body(h.sumedh, h.savar, 1000L))

        val balances = net(h.id)
        assertEquals(1500L, balances[h.savar])
        assertEquals(-1500L, balances[h.sumedh])
    }

    @Test
    fun `invalid requests return 400 and write nothing`() = withApp {
        val h = home()
        val other = home()
        val bad = listOf(
            "zero amount" to body(h.sumedh, h.savar, 0L),
            "negative amount" to body(h.sumedh, h.savar, -5L),
            "same person" to body(h.savar, h.savar, 100L),
            "person from another household" to body(h.sumedh, other.savar, 100L),
            "unknown person" to body(h.sumedh, Uuid.random(), 100L),
            "malformed id" to body(h.sumedh, "not-a-uuid", 100L),
            "missing amount" to body(h.sumedh, h.savar, null),
            "missing people" to body(null, null, 100L),
            "non numeric amount" to body(h.sumedh, h.savar, "abc"),
            "malformed json" to "{not json",
        )
        for ((label, json) in bad) {
            val res = post(h.id, json)
            assertEquals(HttpStatusCode.BadRequest, res.status, label)
            assertEquals("validation_error", res.obj().getValue("code").jsonPrimitive.content, label)
        }
        assertEquals(0, repos.settlements.listByHousehold(h.id).size)
        assertEquals(0, repos.ledger.listByHousehold(h.id).size)
    }

    @Test
    fun `unknown or malformed household returns 404`() = withApp {
        val h = home()
        assertEquals(HttpStatusCode.NotFound, post(Uuid.random(), body(h.sumedh, h.savar, 100L)).status)
        assertEquals(HttpStatusCode.NotFound, post("nope", body(h.sumedh, h.savar, 100L)).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/settlements").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/nope/settlements").status)
    }

    @Test
    fun `listing returns settlements newest first`() = withApp {
        val h = home()
        post(h.id, body(h.sumedh, h.savar, 100L))
        post(h.id, body(h.savar, h.sumedh, 200L))

        val res = client.get("/households/${h.id}/settlements")

        assertEquals(HttpStatusCode.OK, res.status)
        val amounts = Json.parseToJsonElement(res.bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("amountMinor").jsonPrimitive.long }
        assertEquals(listOf(200L, 100L), amounts)
    }

    @Test
    fun `listing an empty household returns an empty array`() = withApp {
        val res = client.get("/households/${home().id}/settlements")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(0, Json.parseToJsonElement(res.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `listing only shows the household's own settlements`() = withApp {
        val a = home()
        val b = home()
        post(a.id, body(a.sumedh, a.savar, 100L))
        post(b.id, body(b.sumedh, b.savar, 999L))

        val amounts = Json.parseToJsonElement(client.get("/households/${a.id}/settlements").bodyAsText()).jsonArray
            .map { it.jsonObject.getValue("amountMinor").jsonPrimitive.long }
        assertEquals(listOf(100L), amounts)
    }
}