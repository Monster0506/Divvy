package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.db.TestDatabase
import com.monster0506.divvy.server.domain.OwnerShare
import com.monster0506.divvy.server.domain.OwnershipSource
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.repository.ExposedRepositories
import com.monster0506.divvy.server.repository.RepositoryTestBase
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ReceiptAssignmentRoutesTest : RepositoryTestBase() {
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val repos = ExposedRepositories(TestDatabase.db.database)
            routing { receiptAssignmentRoutes({ repos.receipts }, { repos.itemOwners }, { repos.people }) }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.putOwners(receipt: Receipt, index: Int, json: String): HttpResponse =
        client.put("/receipts/${receipt.id}/items/${receipt.items[index].id}/owners") {
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `put owners stores shares as user assignments and logs history`() = withApp {
        val (house, people) = household("Ann", "Ben")
        val receipt = receipt(house, people[0], "Milk" to 400L)
        val res = putOwners(
            receipt, 0,
            """{"owners":[{"personId":"${people[0].id}"},{"personId":"${people[1].id}","weight":2}]}""",
        )
        assertEquals(HttpStatusCode.OK, res.status)
        val owners = res.json().getValue("owners").jsonArray
        assertEquals(listOf(1, 2), owners.map { it.jsonObject.getValue("weight").jsonPrimitive.content.toInt() }.sorted())
        assertEquals(setOf("user"), owners.map { it.jsonObject.getValue("source").jsonPrimitive.content }.toSet())
        assertEquals(2, repos.itemOwners.history(receipt.items[0].id).size)
    }

    @Test
    fun `put owners accepts archived household members and rejects foreign or duplicate people`() = withApp {
        val (house, people) = household("Ann", "Ben")
        val (_, others) = household("Zed")
        val receipt = receipt(house, people[0], "Milk" to 400L)
        repos.people.archive(people[1].id)

        assertEquals(HttpStatusCode.OK, putOwners(receipt, 0, """{"owners":[{"personId":"${people[1].id}"}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, putOwners(receipt, 0, """{"owners":[{"personId":"${others[0].id}"}]}""").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            putOwners(receipt, 0, """{"owners":[{"personId":"${people[0].id}"},{"personId":"${people[0].id}"}]}""").status,
        )
        assertEquals(HttpStatusCode.BadRequest, putOwners(receipt, 0, """{"owners":[{"personId":"${people[0].id}","weight":0}]}""").status)
    }

    @Test
    fun `put owners returns 404 for unknown receipt or item`() = withApp {
        val (house, people) = household("Ann")
        val receipt = receipt(house, people[0], "Milk" to 400L)
        val unknown = "00000000-0000-0000-0000-000000000001"
        val body = """{"owners":[]}"""
        val badReceipt = client.put("/receipts/$unknown/items/${receipt.items[0].id}/owners") {
            contentType(ContentType.Application.Json); setBody(body)
        }
        val badItem = client.put("/receipts/${receipt.id}/items/$unknown/owners") {
            contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.NotFound, badReceipt.status)
        assertEquals(HttpStatusCode.NotFound, badItem.status)
    }

    @Test
    fun `confirm with unassigned items returns 400 and leaves the receipt a draft`() = withApp {
        val (house, people) = household("Ann")
        val receipt = receipt(house, people[0], "Milk" to 400L, "Eggs" to 300L)
        repos.itemOwners.setOwners(receipt.items[0].id, listOf(OwnerShare(people[0].id)))

        val res = client.post("/receipts/${receipt.id}/confirm")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals(listOf(receipt.items[1].id.toString()), res.json().getValue("details").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(ReceiptStatus.DRAFT, repos.receipts.findById(receipt.id)?.status)
    }

    @Test
    fun `confirm treats an archived-only owner as unassigned`() = withApp {
        val (house, people) = household("Ann", "Ben")
        val receipt = receipt(house, people[0], "Milk" to 400L)
        repos.itemOwners.setOwners(receipt.items[0].id, listOf(OwnerShare(people[1].id)))
        repos.people.archive(people[1].id)
        assertEquals(HttpStatusCode.BadRequest, client.post("/receipts/${receipt.id}/confirm").status)
    }

    @Test
    fun `confirm moves the receipt to confirmed and records manual observations`() = withApp {
        val (house, people) = household("Ann", "Ben")
        val receipt = receipt(house, people[0], "Milk" to 400L, "Eggs" to 300L)
        putOwners(receipt, 0, """{"owners":[{"personId":"${people[0].id}"}]}""")
        repos.itemOwners.setOwners(
            receipt.items[1].id,
            listOf(OwnerShare(people[1].id, source = OwnershipSource.MODEL, confidence = "0.9".toBigDecimal())),
        )

        val res = client.post("/receipts/${receipt.id}/confirm")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("confirmed", res.json().getValue("status").jsonPrimitive.content)
        receipt.items.forEach { item ->
            val owners = repos.itemOwners.ownersOf(item.id)
            assertEquals(setOf(OwnershipSource.USER), owners.map { it.source }.toSet())
            assertEquals(true, repos.itemOwners.history(item.id).isNotEmpty())
        }
        assertEquals(ReceiptStatus.CONFIRMED, repos.receipts.findById(receipt.id)?.status)
    }

    @Test
    fun `confirmed receipts cannot be confirmed or reassigned again`() = withApp {
        val (house, people) = household("Ann")
        val receipt = receipt(house, people[0], "Milk" to 400L)
        putOwners(receipt, 0, """{"owners":[{"personId":"${people[0].id}"}]}""")
        assertEquals(HttpStatusCode.OK, client.post("/receipts/${receipt.id}/confirm").status)
        assertEquals(HttpStatusCode.Conflict, client.post("/receipts/${receipt.id}/confirm").status)
        assertEquals(HttpStatusCode.Conflict, putOwners(receipt, 0, """{"owners":[]}""").status)
    }

    @Test
    fun `confirm returns 404 for an unknown receipt`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.post("/receipts/00000000-0000-0000-0000-000000000001/confirm").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/receipts/nope/confirm").status)
    }
}
