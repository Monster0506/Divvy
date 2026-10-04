package com.monster0506.divvy.server.ml

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorMlClientTest {
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        requestTimeoutMillis: Long = 5_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorMlClient {
        val engine = MockEngine { request ->
            seen += request
            handler(request)
        }
        return KtorMlClient("http://ml:8000/", HttpClient(engine) { configureMl(2_000, requestTimeoutMillis) })
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun sentJson(request: HttpRequestData): JsonObject =
        mlJson.parseToJsonElement((request.body as TextContent).text).jsonObject

    private val predictRequest = MlPredictRequest(
        householdId = "h-1",
        people = listOf(MlPerson("p1", "Alex"), MlPerson("p2", "Sam")),
        items = listOf(MlItem("i1", "WHOLE MILK 1GAL"), MlItem("i2", "BANANAS")),
    )

    @Test
    fun `predict posts the ML contract and reads owners, confidence and alternatives`() = runBlocking<Unit> {
        val ml = client {
            json(
                """{"model_version":"combined-calibrated-120-ab12cd34","predictions":[
                  {"item_id":"i1","status":"predicted","owners":["p1"],"confidence":0.93,
                   "alternatives":[{"owners":["p1","p2"],"confidence":0.16}]},
                  {"item_id":"i2","status":"unknown","owners":[],"confidence":0.0,"alternatives":[]}],
                  "unexpected_field":true}""",
            )
        }

        val result = ml.predict(predictRequest)

        val response = assertIs<MlResult.Success<MlPredictResponse>>(result).value
        assertEquals("combined-calibrated-120-ab12cd34", response.modelVersion)
        val milk = response.predictions[0]
        assertEquals(MlPredictionStatus.PREDICTED, milk.status)
        assertEquals(listOf("p1"), milk.owners)
        assertEquals(0.93, milk.confidence)
        assertEquals(listOf("p1", "p2"), milk.alternatives.single().owners)
        assertEquals(MlPredictionStatus.UNKNOWN, response.predictions[1].status)

        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://ml:8000/ownership/predict", request.url.toString())
        val body = sentJson(request)
        assertEquals("h-1", body["household_id"]!!.jsonPrimitive.content)
        assertEquals("i1", body["items"]!!.jsonArray[0].jsonObject["item_id"]!!.jsonPrimitive.content)
        assertEquals("Alex", body["people"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert sends snake_case observations and replace only when asked`() = runBlocking<Unit> {
        val ml = client { json("""{"household_id":"h-1","received":1,"history_size":42}""") }
        val observation = MlObservation(
            observationId = "obs-1",
            purchasedOn = "2026-10-03",
            description = "Coke Zero 12-pack",
            memberIds = listOf("p1", "p2"),
            ownerIds = listOf("p2"),
            source = MlAssignmentSource.PREDICTED_CORRECTED,
        )

        val plain = ml.upsertObservations("h-1", listOf(observation))
        val replaced = ml.upsertObservations("h-1", listOf(observation), replace = true)

        assertEquals(42, assertIs<MlResult.Success<MlUpsertResponse>>(plain).value.historySize)
        assertIs<MlResult.Success<MlUpsertResponse>>(replaced)
        assertEquals("http://ml:8000/ownership/households/h-1/observations", seen[0].url.toString())
        assertEquals("http://ml:8000/ownership/households/h-1/observations?replace=true", seen[1].url.toString())
        assertEquals(HttpMethod.Put, seen[0].method)
        val sent = sentJson(seen[0])["observations"]!!.jsonArray.single().jsonObject
        assertEquals("obs-1", sent["observation_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-03", sent["purchased_on"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf("p1", "p2").map { kotlinx.serialization.json.JsonPrimitive(it) }), sent["member_ids"])
        assertEquals("predicted_corrected", sent["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `delete maps 204 to success and 404 to rejected`() = runBlocking<Unit> {
        val ok = client { respond("", HttpStatusCode.NoContent) }
        val missing = client { json("""{"detail":"observation not found"}""", HttpStatusCode.NotFound) }

        assertEquals(MlResult.Success(Unit), ok.deleteObservation("h-1", "obs-1"))
        assertEquals(HttpMethod.Delete, seen.first().method)
        assertEquals("http://ml:8000/ownership/households/h-1/observations/obs-1", seen.first().url.toString())
        assertEquals(MlResult.Rejected(404, "observation not found"), missing.deleteObservation("h-1", "obs-1"))
    }

    @Test
    fun `history reads the size and readiness`() = runBlocking<Unit> {
        val ml = client {
            json("""{"household_id":"h-1","history_size":120,"min_history":30,"ready":true,"model_version":"v1"}""")
        }

        val status = assertIs<MlResult.Success<MlHistoryStatus>>(ml.history("h-1")).value

        assertEquals(120, status.historySize)
        assertTrue(status.ready)
        assertEquals("v1", status.modelVersion)
        assertEquals("http://ml:8000/ownership/households/h-1/history", seen.single().url.toString())
    }

    @Test
    fun `history copes with a missing model version`() = runBlocking<Unit> {
        val ml = client { json("""{"household_id":"h-1","history_size":0,"min_history":30,"ready":false}""") }

        val status = assertIs<MlResult.Success<MlHistoryStatus>>(ml.history("h-1")).value

        assertNull(status.modelVersion)
    }

    @Test
    fun `a validation failure is rejected with the first message`() = runBlocking<Unit> {
        val ml = client {
            json("""{"detail":[{"loc":["body","items"],"msg":"List should have at most 256 items","type":"x"}]}""", HttpStatusCode.UnprocessableEntity)
        }

        val result = ml.predict(predictRequest)

        assertEquals(MlResult.Rejected(422, "List should have at most 256 items"), result)
    }

    @Test
    fun `a rejection with an unreadable body still carries the text`() = runBlocking<Unit> {
        val ml = client { respondError(HttpStatusCode.BadRequest, "plain text problem") }

        assertEquals(MlResult.Rejected(400, "plain text problem"), ml.predict(predictRequest))
    }

    @Test
    fun `server errors are unavailable not rejected`() = runBlocking<Unit> {
        for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable)) {
            val ml = client { respondError(status) }

            val result = ml.predict(predictRequest)

            assertEquals(MlResult.Unavailable("ML service returned ${status.value}"), result)
        }
    }

    @Test
    fun `a connection failure is unavailable and never thrown`() = runBlocking<Unit> {
        val ml = client { throw IOException("connection refused") }

        val result = ml.predict(predictRequest)

        val unavailable = assertIs<MlResult.Unavailable>(result)
        assertTrue("connection refused" in unavailable.reason, unavailable.reason)
    }

    @Test
    fun `a slow ML service times out as unavailable`() = runBlocking<Unit> {
        val ml = client(requestTimeoutMillis = 100) {
            delay(2_000)
            json("""{"model_version":"v","predictions":[]}""")
        }

        val started = System.nanoTime()
        val result = ml.predict(predictRequest)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertIs<MlResult.Unavailable>(result)
        assertTrue(elapsedMillis < 1_500, "gave up after ${elapsedMillis}ms")
    }

    @Test
    fun `a 200 with a body that is not the contract is unavailable`() = runBlocking<Unit> {
        val ml = client { json("""{"unexpected":"shape"}""") }

        assertIs<MlResult.Unavailable>(ml.predict(predictRequest))
        assertIs<MlResult.Unavailable>(client { json("not json at all") }.history("h-1"))
    }

    @Test
    fun `every call fails soft, including push and delete`() = runBlocking<Unit> {
        val ml = client { throw IOException("down") }

        assertIs<MlResult.Unavailable>(ml.upsertObservations("h-1", emptyList()))
        assertIs<MlResult.Unavailable>(ml.deleteObservation("h-1", "x"))
        assertIs<MlResult.Unavailable>(ml.history("h-1"))
    }

    @Test
    fun `a household id is a path segment, not free-form path text`() = runBlocking<Unit> {
        val ml = client { json("""{"household_id":"x","history_size":0,"min_history":30,"ready":false}""") }

        ml.history("a/b ../c")

        val path = seen.single().url.encodedPath
        assertTrue(path.startsWith("/ownership/households/a%2Fb"), path)
        assertTrue(path.endsWith("/history"), path)
    }

    @Test
    fun `the noop client reports the service as not configured`() = runBlocking<Unit> {
        val expected = MlResult.Unavailable("ML service is not configured")

        assertEquals(expected, NoopMlClient.predict(predictRequest))
        assertEquals(expected, NoopMlClient.upsertObservations("h", emptyList()))
        assertEquals(expected, NoopMlClient.deleteObservation("h", "o"))
        assertEquals(expected, NoopMlClient.history("h"))
    }

    @Test
    fun `error messages are shortened and never empty`() {
        assertEquals("no details", errorMessage("   "))
        assertEquals(200, errorMessage("x".repeat(500)).length)
        assertEquals("boom", errorMessage("""{"detail":"boom"}"""))
    }
}
