package com.monster0506.divvy.data.api

import com.monster0506.divvy.data.dto.BalanceDto
import com.monster0506.divvy.data.dto.HouseholdDto
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
        val repo = BalanceRepository(api { respond("""[{"personId":"p1","netCents":250}]""", headers = json) })

        val balances = assertIs<ApiResult.Success<List<BalanceDto>>>(repo.balances("h1"))
        assertEquals(250L, balances.value.single().netCents)
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
