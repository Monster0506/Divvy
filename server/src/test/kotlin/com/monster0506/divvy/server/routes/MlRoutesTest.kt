package com.monster0506.divvy.server.routes

import com.monster0506.divvy.server.domain.CurrencyCode
import com.monster0506.divvy.server.domain.Household
import com.monster0506.divvy.server.ml.FakeHouseholds
import com.monster0506.divvy.server.ml.FakeMl
import com.monster0506.divvy.server.ml.FakePeople
import com.monster0506.divvy.server.ml.FakeReceipts
import com.monster0506.divvy.server.ml.LabelSync
import com.monster0506.divvy.server.ml.MlHistoryStatus
import com.monster0506.divvy.server.ml.MlResult
import com.monster0506.divvy.server.ml.MlResync
import com.monster0506.divvy.server.ml.MlUpsertResponse
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class MlRoutesTest {
    private val household = Household(Uuid.random(), "Home", CurrencyCode("USD"), t0, t0)
    private val tj = person(household.id, "TJ")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ml = FakeMl()
    private val confirmed = receipt(household.id) { listOf(item(it, "Eggs", listOf(tj)), item(it, "Rice", listOf(tj), line = 2)) }

    @AfterTest
    fun stop() = scope.cancel()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            val people = FakePeople(listOf(tj))
            val resync = MlResync(ml, FakeReceipts(listOf(confirmed)), people, LabelSync(ml, people, scope))
            routing { mlRoutes({ FakeHouseholds(listOf(household)) }, { resync }) }
        }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `status compares the ML copy with the database`() = withApp {
        val local = runBlocking {
            MlResync(ml, FakeReceipts(listOf(confirmed)), FakePeople(listOf(tj)), LabelSync(ml, FakePeople(listOf(tj)), scope))
                .observationsFor(household.id)
        }
        ml.historyResult = { MlResult.Success(MlHistoryStatus(it, 2, 30, false, "v7", historyDigest(local))) }

        val response = client.get("/households/${household.id}/ml/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(2, body["databaseLabels"]!!.jsonPrimitive.int)
        assertEquals(2, body["mlLabels"]!!.jsonPrimitive.int)
        assertEquals(true, body["inSync"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["mlAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["mlReady"]!!.jsonPrimitive.boolean)
        assertEquals("v7", body["modelVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `status still answers when the ML service is down`() = withApp {
        ml.historyResult = { MlResult.Unavailable("down") }

        val response = client.get("/households/${household.id}/ml/status")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(false, body["mlAvailable"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["databaseLabels"]!!.jsonPrimitive.int)
        assertEquals(null, body["inSync"])
    }

    @Test
    fun `resync rebuilds the ML copy and reports what was sent`() = withApp {
        ml.upsertResult = { MlResult.Success(MlUpsertResponse(household.id.toString(), 2, 2)) }

        val response = client.post("/households/${household.id}/ml/resync")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json(response.bodyAsText())
        assertEquals(2, body["sent"]!!.jsonPrimitive.int)
        assertEquals(2, body["mlLabels"]!!.jsonPrimitive.int)
        assertEquals(listOf(true), ml.replaces.toList())
    }

    @Test
    fun `resync answers 503 when the ML service is down`() = withApp {
        ml.upsertResult = { MlResult.Unavailable("down") }

        val response = client.post("/households/${household.id}/ml/resync")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("ml_unavailable", json(response.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resync answers 502 when the ML service refuses the history`() = withApp {
        ml.upsertResult = { MlResult.Rejected(422, "bad history") }

        val response = client.post("/households/${household.id}/ml/resync")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("bad history", json(response.bodyAsText())["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown or malformed household is a 404`() = withApp {
        assertEquals(HttpStatusCode.NotFound, client.get("/households/${Uuid.random()}/ml/status").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/households/not-a-uuid/ml/status").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/households/${Uuid.random()}/ml/resync").status)
        assertEquals(0, ml.upserts.size)
    }
}
