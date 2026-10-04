package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.post
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ReceiptReopenRoutesTest {
    private val repos = ExposedRepositories(TestDatabase.db.database)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { receiptReopenRoutes { repos.receipts } }
        }
        block()
    }

    private fun receipt(confirm: Boolean): Receipt = runBlocking {
        val home = repos.households.create("Home", CurrencyCode("USD"))
        val payer = repos.people.add(home.id, "TJ")
        val other = repos.people.add(home.id, "Sam")
        val created = repos.receipts.create(
            NewReceipt(
                home.id, payer.id, "Shop", Instant.parse("2026-10-01T12:00:00Z"), CurrencyCode("USD"), null, null, 1000,
                listOf(NewReceiptItem("Eggs", 1000, owners = listOf(OwnerShare(other.id)))),
            ),
        )
        if (confirm) repos.receipts.confirm(created.id)!! else created
    }

    @Test
    fun `reopening a confirmed receipt makes it a draft again and cancels its balances`() = withApp {
        val confirmed = receipt(confirm = true)

        val response = client.post("/receipts/${confirmed.id}/reopen")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("draft", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("draft", runBlocking { repos.receipts.findById(confirmed.id)!!.status.dbValue })
    }

    @Test
    fun `a draft cannot be reopened`() = withApp {
        val draft = receipt(confirm = false)

        assertEquals(HttpStatusCode.Conflict, client.post("/receipts/${draft.id}/reopen").status)
    }

    @Test
    fun `an unknown receipt or a bad id is a 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.post("/receipts/${Uuid.random()}/reopen").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/receipts/nope/reopen").status)
    }
}
