package com.monster0506.divvy.data.api

import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.HouseholdDto
import com.monster0506.divvy.data.dto.AcceptedPredictionsDto
import com.monster0506.divvy.data.dto.ConfirmedReceiptDto
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.data.dto.ReceiptUploadDto
import com.monster0506.divvy.data.repository.BalanceRepository
import com.monster0506.divvy.data.repository.HouseholdRepository
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.repository.ReceiptRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DivvyApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DivvyApi(createHttpClient(ApiConfig("http://test", "key"), MockEngine(handler)))

    private fun failure(result: ApiResult<*>): ApiFailure = assertIs<ApiResult.Failure>(result).failure

    @Test
    fun household_is_decoded() = runTest {
        val repo = HouseholdRepository(
            api { respond("""{"id":"h1","name":"Flat","defaultCurrency":"USD","extra":true}""", headers = json) },
        )

        val household = assertIs<ApiResult.Success<HouseholdDto>>(repo.get("h1")).value
        assertEquals("Flat", household.name)
    }

    @Test
    fun requests_send_json_bodies_and_paths() = runTest {
        var method: HttpMethod? = null
        var path = ""
        var body = ""
        val repo = HouseholdRepository(
            api { request ->
                method = request.method
                path = request.url.encodedPath
                body = request.body.toByteArray().decodeToString()
                respond("""{"id":"p1","householdId":"h1","displayName":"Ann"}""", headers = json)
            },
        )

        assertIs<ApiResult.Success<*>>(repo.renamePerson("h1", "p1", "Ann"))

        assertEquals(HttpMethod.Patch, method)
        assertEquals("/households/h1/people/p1", path)
        assertEquals("""{"displayName":"Ann"}""", body)
    }

    @Test
    fun unauthorised_maps_to_unauthorised() = runTest {
        val repo = ReceiptRepository(api { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(ApiFailure.Unauthorised, failure(repo.get("r1")))
    }

    @Test
    fun client_error_with_body_maps_to_validation() = runTest {
        val repo = HouseholdRepository(
            api {
                respond(
                    """{"code":"validation_error","message":"name is required"}""",
                    HttpStatusCode.BadRequest,
                    json,
                )
            },
        )

        val validation = assertIs<ApiFailure.Validation>(failure(repo.create("")))
        assertEquals("validation_error", validation.error.code)
        assertEquals("name is required", validation.error.message)
    }

    @Test
    fun client_error_without_body_still_maps_to_validation() = runTest {
        val repo = HouseholdRepository(api { respond("<html>", HttpStatusCode.NotFound) })

        val validation = assertIs<ApiFailure.Validation>(failure(repo.get("missing")))
        assertEquals("http_404", validation.error.code)
    }

    @Test
    fun server_error_maps_to_server_error() = runTest {
        val repo = BalanceRepository(api { respond("boom", HttpStatusCode.InternalServerError) })

        assertEquals(ApiFailure.ServerError(500), failure(repo.balances("h1")))
    }

    @Test
    fun malformed_success_body_maps_to_server_error() = runTest {
        val repo = BalanceRepository(api { respond("""{"not":"a list"}""", headers = json) })

        assertEquals(ApiFailure.ServerError(200), failure(repo.balances("h1")))
    }

    @Test
    fun network_failure_maps_to_offline() = runTest {
        val repo = HouseholdRepository(api { throw RuntimeException("no route to host") })

        assertEquals(ApiFailure.Offline, failure(repo.get("h1")))
    }

    @Test
    fun balances_are_decoded() = runTest {
        val repo = BalanceRepository(api { respond(
            """{"balances":[{"currency":"USD","people":[{"personId":"p1","displayName":"TJ","netMinor":250,"owes":[],"owedBy":[{"personId":"p2","amountMinor":250}]}]}]}""",
            headers = json,
        ) })

        val balances = assertIs<ApiResult.Success<BalancesDto>>(repo.balances("h1"))
        val person = balances.value.balances.single().people.single()
        assertEquals(250L, person.netMinor)
        assertEquals("p2", person.owedBy.single().personId)
    }

    @Test
    fun receipt_photo_is_sent_as_multipart_and_processing_id_is_decoded() = runTest {
        var contentType = ""
        var body = ""
        var path = ""
        var apiKey = ""
        val repo = ReceiptRepository(api { request ->
            contentType = request.body.contentType.toString()
            body = request.body.toByteArray().decodeToString()
            path = request.url.encodedPath
            apiKey = request.headers[API_KEY_HEADER].orEmpty()
            respond("""{"id":"receipt-1","status":"processing"}""", HttpStatusCode.Created, json)
        })

        val result = assertIs<ApiResult.Success<ReceiptUploadDto>>(repo.upload("home-1", "jpeg contents".encodeToByteArray()))
        assertEquals("/households/home-1/receipts", path)
        assertEquals("key", apiKey)
        assertTrue(contentType.startsWith(ContentType.MultiPart.FormData.toString()))
        assertTrue(body.contains("name=image"))
        assertTrue(body.contains("receipt.jpg"))
        assertEquals("receipt-1", result.value.id)
    }

    @Test
    fun confirming_a_receipt_posts_and_decodes_the_confirmed_status() = runTest {
        var method: HttpMethod? = null
        var path = ""
        val repo = ReceiptRepository(
            api { request ->
                method = request.method
                path = request.url.encodedPath
                respond("""{"id":"r1","status":"confirmed","confirmedAt":"2026-10-04T10:00:00Z","items":[]}""", headers = json)
            },
        )

        val confirmed = assertIs<ApiResult.Success<ConfirmedReceiptDto>>(repo.confirm("r1")).value

        assertEquals(HttpMethod.Post, method)
        assertEquals("/receipts/r1/confirm", path)
        assertEquals(ReceiptStatusDto.Confirmed, confirmed.status)
    }

    @Test
    fun an_unconfirmed_items_conflict_is_a_validation_failure_with_the_item_ids() = runTest {
        val repo = ReceiptRepository(
            api {
                respond(
                    """{"code":"unconfirmed_items","message":"1 item(s) need to be confirmed first","details":["i1"]}""",
                    HttpStatusCode.Conflict,
                    json,
                )
            },
        )

        val failure = assertIs<ApiFailure.Validation>((repo.confirm("r1") as ApiResult.Failure).failure)

        assertEquals("unconfirmed_items", failure.error.code)
        assertEquals(listOf("i1"), failure.error.details)
    }

    @Test
    fun retry_posts_to_the_household_receipt_and_decodes_processing() = runTest {
        var path = ""
        val repo = ReceiptRepository(
            api { request ->
                path = request.url.encodedPath
                respond("""{"id":"r1","status":"processing"}""", HttpStatusCode.Accepted, json)
            },
        )

        val result = assertIs<ApiResult.Success<ReceiptUploadDto>>(repo.retry("h1", "r1")).value

        assertEquals("/households/h1/receipts/r1/retry", path)
        assertEquals("processing", result.status)
    }

    @Test
    fun predictions_are_read_and_accepted_per_household_receipt() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        var body = ""
        val repo = ReceiptRepository(
            api { request ->
                seen += request.method to request.url.encodedPath
                if (request.method == HttpMethod.Post) body = request.body.toByteArray().decodeToString()
                if (request.method == HttpMethod.Post) respond("""{"confirmedItemIds":["i1"]}""", headers = json)
                else respond("""{"receiptId":"r1","items":[{"itemId":"i1","state":"predicted","ownerIds":["p1"],"confidence":0.7,"needsConfirmation":true}]}""", headers = json)
            },
        )

        val predictions = assertIs<ApiResult.Success<PredictionsDto>>(repo.predictions("h1", "r1")).value
        val accepted = assertIs<ApiResult.Success<AcceptedPredictionsDto>>(repo.acceptPredictions("h1", "r1", listOf("i1"))).value

        assertEquals(true, predictions.items.single().needsConfirmation)
        assertEquals(listOf("i1"), accepted.confirmedItemIds)
        assertEquals(
            listOf(
                HttpMethod.Get to "/households/h1/receipts/r1/predictions",
                HttpMethod.Post to "/households/h1/receipts/r1/predictions/accept",
            ),
            seen,
        )
        assertTrue(""""itemIds":["i1"]""" in body)
    }

    @Test
    fun setting_item_owners_puts_the_owner_list() = runTest {
        var method: HttpMethod? = null
        var path = ""
        var body = ""
        val repo = ReceiptRepository(
            api { request ->
                method = request.method
                path = request.url.encodedPath
                body = request.body.toByteArray().decodeToString()
                respond("""{"itemId":"i1","owners":[{"personId":"p1","weight":1},{"personId":"p2","weight":2}]}""", headers = json)
            },
        )

        val result = repo.setItemOwners("r1", "i1", listOf(ItemOwnerDto("p1"), ItemOwnerDto("p2", 2)))

        val saved = assertIs<ApiResult.Success<ItemOwnersDto>>(result).value
        assertEquals(HttpMethod.Put, method)
        assertEquals("/receipts/r1/items/i1/owners", path)
        assertTrue(""""personId":"p2"""" in body)
        assertEquals(listOf("p1", "p2"), saved.owners.map { it.personId })
    }
}
