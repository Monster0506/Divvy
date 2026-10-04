package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.domain.ReceiptStatus
import com.monster0506.divvy.server.ml.ExtractionService
import com.monster0506.divvy.server.ml.FakeItemOwners
import com.monster0506.divvy.server.ml.FakeMl
import com.monster0506.divvy.server.ml.FakePeople
import com.monster0506.divvy.server.ml.FakePredictions
import com.monster0506.divvy.server.ml.FakeReceipts
import com.monster0506.divvy.server.ml.FakeSettings
import com.monster0506.divvy.server.ml.LabelSync
import com.monster0506.divvy.server.ml.MemoryStorage
import com.monster0506.divvy.server.ml.MlHistoryStatus
import com.monster0506.divvy.server.ml.MlResult
import com.monster0506.divvy.server.ml.MlResync
import com.monster0506.divvy.server.ml.PredictionService
import com.monster0506.divvy.server.ml.historyDigest
import com.monster0506.divvy.server.ml.receipt
import com.monster0506.divvy.server.ml.t0
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ReceiptRetryRoutesTest {
    private val household = Household(Uuid.random(), "Home", CurrencyCode("USD"), t0, t0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val failed = receipt(household.id, ReceiptStatus.FAILED, confirmedAt = null) { emptyList() }
        .copy(failureReason = "x")
    private val draft = receipt(household.id, ReceiptStatus.DRAFT, confirmedAt = null) { emptyList() }
    private val receipts = FakeReceipts(listOf(failed, draft))

    @AfterTest
    fun stop() = scope.cancel()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val people = FakePeople(emptyList())
            val resync = MlResync(ml, receipts, people, LabelSync(ml, people, scope))
            ml.historyResult = { MlResult.Success(MlHistoryStatus(it, 0, 30, false, null, historyDigest(emptyList()))) }
            val predictions =
                PredictionService(ml, resync, receipts, people, FakeItemOwners(), FakePredictions(), FakeSettings())
            val extraction = ExtractionService(ml, receipts, MemoryStorage(), predictions, scope)
            routing { receiptRetryRoutes({ receipts }, { extraction }) }
        }
        block()
    }

    private fun path(id: Uuid, home: Uuid = household.id) = "/households/$home/receipts/$id/retry"

    @Test
    fun `retrying a failed receipt answers 202 with it processing again`() = withApp {
        val response = client.post(path(failed.id))

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(failed.id.toString(), body.getValue("id").jsonPrimitive.content)
        assertEquals("processing", body.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `a receipt that did not fail is a 409`() = withApp {
        assertEquals(HttpStatusCode.Conflict, client.post(path(draft.id)).status)
    }

    @Test
    fun `an unknown receipt, another household or a bad id is a 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.post(path(Uuid.random())).status)
        assertEquals(HttpStatusCode.NotFound, client.post(path(failed.id, Uuid.random())).status)
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${household.id}/receipts/nope/retry").status)
    }
}
