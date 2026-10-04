package com.monster0506.divvy.server.ml

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs the client against a real ML service. Skipped unless `ML_SERVICE_URL` is set, so CI (which
 * has no ML service in this job) is unaffected. To run it:
 *
 *     cd ml && uvicorn divvy_ml.app:app --port 8000
 *     ML_SERVICE_URL=http://127.0.0.1:8000 ./gradlew :server:test --tests "*MlServiceContractTest"
 */
class MlServiceContractTest {
    private val url = System.getenv("ML_SERVICE_URL")

    private fun client(): KtorMlClient {
        assumeTrue("ML_SERVICE_URL is not set", !url.isNullOrBlank())
        return KtorMlClient.create(url)
    }

    private val sumedh = UUID.randomUUID().toString()
    private val tj = UUID.randomUUID().toString()
    private val members = listOf(sumedh, tj)

    private fun observation(n: Int, description: String, owner: String) = MlObservation(
        observationId = UUID.randomUUID().toString(),
        purchasedOn = "2026-09-%02d".format(1 + n % 28),
        description = description,
        memberIds = members,
        ownerIds = listOf(owner),
        source = MlAssignmentSource.MANUAL,
    )

    @Test
    fun `push history, predict from it, correct a label and delete one`(): Unit = runBlocking {
        val ml = client()
        val household = UUID.randomUUID().toString()
        val rows = (0 until 40).map { n ->
            if (n % 2 == 0) observation(n, "Coke Zero 12-pack", tj) else observation(n, "Paneer block", sumedh)
        }

        val pushed = ml.upsertObservations(household, rows)
        assertEquals(40, assertIs<MlResult.Success<MlUpsertResponse>>(pushed).value.historySize)

        val status = assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(household)).value
        assertEquals(40, status.historySize)
        assertTrue(status.ready)

        val request = MlPredictRequest(
            householdId = household,
            people = listOf(MlPerson(sumedh, "Sumedh"), MlPerson(tj, "TJ")),
            items = listOf(MlItem("a", "Coke Zero 12-pack"), MlItem("b", "Paneer block"), MlItem("c", "zqxv wjkp mnbh")),
        )
        val response = assertIs<MlResult.Success<MlPredictResponse>>(ml.predict(request)).value
        val byItem = response.predictions.associateBy { it.itemId }

        assertTrue(response.modelVersion.isNotBlank())
        assertEquals(listOf(tj), byItem.getValue("a").owners)
        assertEquals(listOf(sumedh), byItem.getValue("b").owners)
        assertEquals(MlPredictionStatus.UNKNOWN, byItem.getValue("c").status)

        val corrected = rows.first().copy(ownerIds = listOf(sumedh), source = MlAssignmentSource.PREDICTED_CORRECTED)
        assertEquals(40, assertIs<MlResult.Success<MlUpsertResponse>>(ml.upsertObservations(household, listOf(corrected))).value.historySize)
        assertEquals(MlResult.Success(Unit), ml.deleteObservation(household, rows.last().observationId))
        assertEquals(39, assertIs<MlResult.Success<MlHistoryStatus>>(ml.history(household)).value.historySize)
        assertIs<MlResult.Rejected>(ml.deleteObservation(household, rows.last().observationId))

        val replaced = ml.upsertObservations(household, rows.take(5), replace = true)
        assertEquals(5, assertIs<MlResult.Success<MlUpsertResponse>>(replaced).value.historySize)
        ml.close()
    }

    @Test
    fun `an unreachable ML service is unavailable not an exception`(): Unit = runBlocking {
        val ml = KtorMlClient.create("http://127.0.0.1:1", connectTimeoutMillis = 200, requestTimeoutMillis = 500)

        assertIs<MlResult.Unavailable>(ml.history("h"))
        ml.close()
    }
}
