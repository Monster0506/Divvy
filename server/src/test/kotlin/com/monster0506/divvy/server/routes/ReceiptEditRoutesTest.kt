package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.NewReceipt
import com.monster0506.divvy.server.domain.NewReceiptItem
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.Person
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.repository.ExposedRepositories
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptEditRoutesTest {
    private val repos = ExposedRepositories(TestDatabase.db.database)

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            routing { receiptEditRoutes({ repos.receipts }, { repos.people }) }
        }
        block()
    }

    private class Fixture(val receipt: Receipt, val people: List<Person>)

    /** Milk 400, Bread 250 and Eggs 300 with $10.00 subtotal, $0.50 tax, $10.50 total. */
    private fun fixture(): Fixture = runBlocking {
        val household = repos.households.create("Flat", CurrencyCode("USD"))
        val people = listOf("Ann", "Bo").map { repos.people.add(household.id, it) }
        val receipt = repos.receipts.create(
            NewReceipt(
                householdId = household.id,
                paidByPersonId = people[0].id,
                merchantName = "Corner Shop",
                purchasedAt = Instant.parse("2026-09-30T17:45:00Z"),
                currency = CurrencyCode("USD"),
                subtotalMinor = 950,
                taxMinor = 50,
                totalMinor = 1000,
                items = listOf(
                    NewReceiptItem("Milk", 400, BigDecimal.ONE, 400, owners = listOf(OwnerShare(people[0].id))),
                    NewReceiptItem("Bread", 250, BigDecimal.ONE, 250),
                    NewReceiptItem("Eggs", 300, BigDecimal.ONE, 300),
                ),
            ),
        )
        Fixture(receipt, people)
    }

    private suspend fun ApplicationTestBuilder.patch(path: String, json: String): HttpResponse =
        client.patch(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun ApplicationTestBuilder.post(path: String, json: String): HttpResponse =
        client.post(path) { contentType(ContentType.Application.Json); setBody(json) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.reconciliation() = getValue("reconciliation").jsonObject

    private fun JsonObject.itemDescriptions() =
        getValue("items").jsonArray.map { it.jsonObject.getValue("description").jsonPrimitive.content }

    @Test
    fun `patching the receipt persists the fields and reports reconciliation`() = withApp {
        val f = fixture()
        val res = patch(
            "/receipts/${f.receipt.id}",
            """{"merchantName":" Big Market ","purchasedAt":"2026-10-01T10:00:00Z",
                "paidByPersonId":"${f.people[1].id}","taxMinor":100,"totalMinor":1050}""",
        )
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.json()
        assertEquals("Big Market", body.getValue("merchantName").jsonPrimitive.content)
        assertEquals("2026-10-01T10:00:00Z", body.getValue("purchasedAt").jsonPrimitive.content)
        assertEquals(f.people[1].id.toString(), body.getValue("paidByPersonId").jsonPrimitive.content)
        assertEquals(100, body.getValue("taxMinor").jsonPrimitive.long)
        assertEquals(1050, body.getValue("totalMinor").jsonPrimitive.long)
        assertTrue(body.reconciliation().getValue("reconciled").jsonPrimitive.boolean)

        val stored = repos.receipts.findById(f.receipt.id)!!
        assertEquals("Big Market", stored.merchantName)
        assertEquals(f.people[1].id, stored.paidByPersonId)
        assertEquals(1050, stored.total.amountMinor)
    }

    @Test
    fun `reconciliation reflects items, tax and total`() = withApp {
        val f = fixture()
        // Items sum to 950 and tax is 50, so a total of 1000 reconciles.
        val res = patch("/receipts/${f.receipt.id}", """{"totalMinor":1000}""")
        val rec = res.json().reconciliation()
        assertEquals(950, rec.getValue("itemsTotalMinor").jsonPrimitive.long)
        assertEquals(0, rec.getValue("totalDifferenceMinor").jsonPrimitive.long)
        assertEquals(0, rec.getValue("subtotalDifferenceMinor").jsonPrimitive.long)
        assertTrue(rec.getValue("reconciled").jsonPrimitive.boolean)
    }

    @Test
    fun `patch validates input`() = withApp {
        val f = fixture()
        val path = "/receipts/${f.receipt.id}"
        assertEquals(HttpStatusCode.BadRequest, patch(path, "{}").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, "nope").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, """{"merchantName":"  "}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, """{"purchasedAt":"yesterday"}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, """{"totalMinor":-1}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, """{"taxMinor":-5}""").status)
        assertEquals(HttpStatusCode.BadRequest, patch(path, """{"paidByPersonId":"x"}""").status)
    }

    @Test
    fun `payer from another household is rejected`() = withApp {
        val f = fixture()
        val other = repos.households.create("Other", CurrencyCode("USD"))
        val stranger = repos.people.add(other.id, "Zed")
        val res = patch("/receipts/${f.receipt.id}", """{"paidByPersonId":"${stranger.id}"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(f.people[0].id, repos.receipts.findById(f.receipt.id)!!.paidByPersonId)
    }

    @Test
    fun `unknown receipts and items return 404`() = withApp {
        val f = fixture()
        val unknown = "00000000-0000-0000-0000-000000000001"
        assertEquals(HttpStatusCode.NotFound, patch("/receipts/$unknown", """{"totalMinor":1}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/receipts/not-a-uuid", """{"totalMinor":1}""").status)
        assertEquals(HttpStatusCode.NotFound, post("/receipts/$unknown/items", """{"description":"A","totalMinor":1}""").status)
        assertEquals(HttpStatusCode.NotFound, patch("/receipts/${f.receipt.id}/items/$unknown", """{"totalMinor":1}""").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/receipts/${f.receipt.id}/items/$unknown").status)
    }

    @Test
    fun `adding an item appends a line and updates reconciliation`() = withApp {
        val f = fixture()
        val res = post(
            "/receipts/${f.receipt.id}/items",
            """{"description":" Apples ","totalMinor":150,"quantity":1.5,"unitPriceMinor":100}""",
        )
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.json()
        val items = body.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Milk", "Bread", "Eggs", "Apples"), body.itemDescriptions())
        assertEquals(4, items.last().getValue("lineNumber").jsonPrimitive.long)
        assertEquals(1.5, items.last().getValue("quantity").jsonPrimitive.content.toDouble())
        assertEquals(1100, body.reconciliation().getValue("itemsTotalMinor").jsonPrimitive.long)
        assertFalse(body.reconciliation().getValue("reconciled").jsonPrimitive.boolean)
        assertEquals(4, repos.receipts.findById(f.receipt.id)!!.items.size)
    }

    @Test
    fun `item creation is validated`() = withApp {
        val f = fixture()
        val path = "/receipts/${f.receipt.id}/items"
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"totalMinor":100}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"description":"A"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"description":"A","totalMinor":1,"quantity":0}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"description":"A","totalMinor":1,"discountMinor":-1}""").status)
        assertEquals(HttpStatusCode.Created, post(path, """{"description":"Coupon","totalMinor":-100}""").status)
    }

    @Test
    fun `patching an item changes it and keeps its owners`() = withApp {
        val f = fixture()
        val milk = f.receipt.items.first()
        val res = patch(
            "/receipts/${f.receipt.id}/items/${milk.id}",
            """{"description":"Oat milk","totalMinor":450,"discountMinor":50,"quantity":2}""",
        )
        assertEquals(HttpStatusCode.OK, res.status)
        val stored = repos.receipts.findById(f.receipt.id)!!.items.first { it.id == milk.id }
        assertEquals("Oat milk", stored.description)
        assertEquals(450, stored.total.amountMinor)
        assertEquals(50, stored.discount.amountMinor)
        assertEquals(0, BigDecimal(2).compareTo(stored.quantity))
        assertEquals(listOf(f.people[0].id), stored.owners.map { it.personId })
        assertEquals(1000, res.json().reconciliation().getValue("itemsTotalMinor").jsonPrimitive.long)
        assertEquals(HttpStatusCode.BadRequest, patch("/receipts/${f.receipt.id}/items/${milk.id}", "{}").status)
    }

    @Test
    fun `deleting an item removes it`() = withApp {
        val f = fixture()
        val bread = f.receipt.items[1]
        val res = client.delete("/receipts/${f.receipt.id}/items/${bread.id}")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(listOf("Milk", "Eggs"), res.json().itemDescriptions())
        assertEquals(700, res.json().reconciliation().getValue("itemsTotalMinor").jsonPrimitive.long)
        assertEquals(2, repos.receipts.findById(f.receipt.id)!!.items.size)
    }

    @Test
    fun `merging items sums them into the first and keeps its owners`() = withApp {
        val f = fixture()
        val (milk, bread, eggs) = f.receipt.items
        val res = post(
            "/receipts/${f.receipt.id}/items/merge",
            """{"itemIds":["${eggs.id}","${milk.id}"],"description":"Dairy"}""",
        )
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(listOf("Dairy", "Bread"), res.json().itemDescriptions())
        val merged = repos.receipts.findById(f.receipt.id)!!.items.first()
        assertEquals(milk.id, merged.id)
        assertEquals(700, merged.total.amountMinor)
        assertEquals(0, BigDecimal(2).compareTo(merged.quantity))
        assertEquals(listOf(f.people[0].id), merged.owners.map { it.personId })
        assertEquals(950, res.json().reconciliation().getValue("itemsTotalMinor").jsonPrimitive.long)
        assertEquals(bread.id, repos.receipts.findById(f.receipt.id)!!.items.last().id)
    }

    @Test
    fun `merge rejects bad item lists`() = withApp {
        val f = fixture()
        val path = "/receipts/${f.receipt.id}/items/merge"
        val milk = f.receipt.items.first().id
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"itemIds":["$milk"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"itemIds":["$milk","$milk"]}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(path, """{"itemIds":["$milk","nope"]}""").status)
        assertEquals(HttpStatusCode.NotFound, post(path, """{"itemIds":["$milk","00000000-0000-0000-0000-000000000001"]}""").status)
        assertEquals(3, repos.receipts.findById(f.receipt.id)!!.items.size)
    }

    @Test
    fun `confirmed receipts reject every edit with 409`() = withApp {
        val f = fixture()
        repos.receipts.confirm(f.receipt.id)
        val id = f.receipt.id
        val milk = f.receipt.items.first().id
        val bread = f.receipt.items[1].id
        assertEquals(HttpStatusCode.Conflict, patch("/receipts/$id", """{"totalMinor":1}""").status)
        assertEquals(HttpStatusCode.Conflict, post("/receipts/$id/items", """{"description":"A","totalMinor":1}""").status)
        assertEquals(HttpStatusCode.Conflict, patch("/receipts/$id/items/$milk", """{"totalMinor":1}""").status)
        assertEquals(HttpStatusCode.Conflict, client.delete("/receipts/$id/items/$milk").status)
        assertEquals(HttpStatusCode.Conflict, post("/receipts/$id/items/merge", """{"itemIds":["$milk","$bread"]}""").status)
        val stored = repos.receipts.findById(id)!!
        assertEquals(1000, stored.total.amountMinor)
        assertEquals(3, stored.items.size)
    }
}
