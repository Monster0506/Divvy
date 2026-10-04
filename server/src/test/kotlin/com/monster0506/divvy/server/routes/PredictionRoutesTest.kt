package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.Receipt
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.ml.FakeHouseholds
import com.monster0506.divvy.server.ml.FakeItemOwners
import com.monster0506.divvy.server.ml.FakeMl
import com.monster0506.divvy.server.ml.FakePeople
import com.monster0506.divvy.server.ml.FakePredictions
import com.monster0506.divvy.server.ml.FakeReceipts
import com.monster0506.divvy.server.ml.LabelSync
import com.monster0506.divvy.server.ml.MlHistoryStatus
import com.monster0506.divvy.server.ml.MlItemPrediction
import com.monster0506.divvy.server.ml.MlPredictResponse
import com.monster0506.divvy.server.ml.MlPredictionStatus
import com.monster0506.divvy.server.ml.MlResult
import com.monster0506.divvy.server.ml.MlResync
import com.monster0506.divvy.server.ml.MlStats
import com.monster0506.divvy.server.ml.PredictionService
import com.monster0506.divvy.server.ml.historyDigest
import com.monster0506.divvy.server.ml.item
import com.monster0506.divvy.server.ml.person
import com.monster0506.divvy.server.ml.receipt
import com.monster0506.divvy.server.ml.t0
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PredictionRoutesTest {
    private val household = Household(Uuid.random(), "Home", CurrencyCode("USD"), t0, t0)
    private val tj = person(household.id, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val draft: Receipt = receipt(household.id, ReceiptStatus.DRAFT, confirmedAt = null) {
        listOf(item(it, "Eggs", emptyList(), null, 1))
    }
    private val confirmed: Receipt = receipt(household.id) { listOf(item(it, "Rice", listOf(tj))) }

    @AfterTest
    fun stop() = scope.cancel()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val people = FakePeople(listOf(tj))
            val receipts = FakeReceipts(listOf(draft, confirmed))
            val resync = MlResync(ml, receipts, people, LabelSync(ml, people, scope))
            val predictions = FakePredictions()
            val service = PredictionService(ml, resync, receipts, people, FakeItemOwners(), predictions, now = { t0 })
            routing {
                predictionRoutes({ receipts }, { service })
                mlStatsRoutes({ FakeHouseholds(listOf(household)) }, { MlStats(receipts, predictions) })
            }
        }
        ml.historyResult = { MlResult.Success(MlHistoryStatus(it, 0, 30, false, null, historyDigest(emptyList()))) }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun path(receipt: Receipt, home: Uuid = household.id) = "/households/$home/receipts/${receipt.id}/predictions"

    @Test
    fun `POST predicts, assigns and reports each item`() = withApp {
        ml.predictResult = { request ->
            MlResult.Success(
                MlPredictResponse(
                    "v1",
                    request.items.map { MlItemPrediction(it.itemId, MlPredictionStatus.PREDICTED, listOf(tj.id.toString()), 0.93) },
                ),
            )
        }

        val response = client.post(path(draft))

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(true, body["mlAvailable"]!!.jsonPrimitive.boolean)
        assertEquals("v1", body["modelVersion"]!!.jsonPrimitive.content)
        val item = body["items"]!!.jsonArray.single().jsonObject
        assertEquals("predicted", item["state"]!!.jsonPrimitive.content)
        assertEquals(listOf(tj.id.toString()), item["ownerIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `POST with the ML service down still answers 200 with a notice`() = withApp {
        ml.predictResult = { MlResult.Unavailable("down") }

        val response = client.post(path(draft))

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(false, body["mlAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["notice"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `POST on a confirmed receipt is a 409`() = withApp {
        val response = client.post(path(confirmed))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("not_draft", json(response.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown receipt, another household's receipt or a bad id is a 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${household.id}/receipts/${Uuid.random()}/predictions").status)
        assertEquals(HttpStatusCode.NotFound, client.post(path(draft, Uuid.random())).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${household.id}/receipts/nope/predictions").status)
    }

    @Test
    fun `GET returns what was stored without asking the model`() = withApp {
        ml.predictResult = { request ->
            MlResult.Success(MlPredictResponse("v1", request.items.map { MlItemPrediction(it.itemId, MlPredictionStatus.UNKNOWN, emptyList(), 0.0) }))
        }
        client.post(path(draft))
        val asked = ml.predictRequests.size

        val response = client.get(path(draft))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(asked, ml.predictRequests.size)
        assertEquals("unknown", json(response.bodyAsText())["items"]!!.jsonArray.single().jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stats count labelled items and stored predictions`() = withApp {
        val response = client.get("/households/${household.id}/ml/stats")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(1, body["labelledItems"]!!.jsonPrimitive.int)
        assertEquals(0, body["predictionsStored"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stats for an unknown household is a 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/ml/stats").status)
    }
}
