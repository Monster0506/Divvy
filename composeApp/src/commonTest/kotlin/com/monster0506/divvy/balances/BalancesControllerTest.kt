package com.monster0506.divvy.balances

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.CurrencyBalancesDto
import com.monster0506.divvy.data.dto.PersonBalanceDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun person(id: String, name: String, net: Long) = PersonBalanceDto(id, name, net)

private fun usd(vararg people: PersonBalanceDto) = BalancesDto(listOf(CurrencyBalancesDto("USD", people.toList())))

class BalancesControllerTest {
    private val asked = mutableListOf<String>()
    private var answer: ApiResult<BalancesDto> = ApiResult.Success(usd())

    private fun TestScope.controller() = BalancesController({ id -> asked += id; answer }, this)

    @Test
    fun amounts_match_the_server_to_the_cent_and_the_person_owed_most_comes_first() = runTest {
        answer = ApiResult.Success(usd(person("p1", "TJ", -1234), person("p2", "Sam", 1301), person("p3", "Ana", -67)))
        val c = controller()

        c.open("h1")
        assertEquals(BalancesPhase.Loading, c.state.value.phase)
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(BalancesPhase.Ready, s.phase)
        assertEquals(listOf("Sam" to 1301, "Ana" to -67, "TJ" to -1234), s.balances.map { it.person.name to it.netCents })
        assertFalse(s.settled)
        assertEquals(listOf("h1"), asked)
    }

    @Test
    fun a_household_with_nothing_owed_is_settled() = runTest {
        answer = ApiResult.Success(usd(person("p1", "TJ", 0), person("p2", "Sam", 0)))
        val c = controller()

        c.open("h1")
        advanceUntilIdle()

        assertTrue(c.state.value.settled)
        assertEquals(2, c.state.value.balances.size)
    }

    @Test
    fun a_failed_first_load_says_why_and_can_be_retried() = runTest {
        answer = ApiResult.Failure(ApiFailure.Offline)
        val c = controller()
        c.open("h1")
        advanceUntilIdle()
        assertEquals(BalancesPhase.Failed, c.state.value.phase)
        assertTrue(c.state.value.message!!.startsWith("Could not reach the server"))

        answer = ApiResult.Success(usd(person("p1", "TJ", 500)))
        c.refresh()
        advanceUntilIdle()

        assertEquals(BalancesPhase.Ready, c.state.value.phase)
        assertEquals(null, c.state.value.message)
    }

    @Test
    fun a_failed_reload_keeps_the_balances_already_shown() = runTest {
        answer = ApiResult.Success(usd(person("p1", "TJ", 500), person("p2", "Sam", -500)))
        val c = controller()
        c.open("h1")
        advanceUntilIdle()

        answer = ApiResult.Failure(ApiFailure.ServerError(503))
        c.refresh()
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(BalancesPhase.Ready, s.phase)
        assertEquals(2, s.balances.size)
        assertFalse(s.refreshing)
        assertTrue(s.message!!.startsWith("The server had a problem"))
    }

    @Test
    fun a_reload_shows_it_is_refreshing_without_clearing_the_list() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val c = BalancesController({
            if (++calls > 1) gate.await()
            ApiResult.Success(usd(person("p1", "TJ", 500)))
        }, this)
        c.open("h1")
        advanceUntilIdle()

        c.refresh()
        advanceUntilIdle()
        assertTrue(c.state.value.refreshing)
        assertEquals(1, c.state.value.balances.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(c.state.value.refreshing)
    }

    @Test
    fun opening_another_household_drops_the_old_balances() = runTest {
        answer = ApiResult.Success(usd(person("p1", "TJ", 500)))
        val c = controller()
        c.open("h1")
        advanceUntilIdle()

        answer = ApiResult.Failure(ApiFailure.Offline)
        c.open("h2")
        advanceUntilIdle()

        assertEquals(BalancesPhase.Failed, c.state.value.phase)
        assertTrue(c.state.value.balances.isEmpty())
    }

    @Test
    fun dollars_are_shown_when_other_currencies_exist_and_the_rest_are_flagged() = runTest {
        answer = ApiResult.Success(
            BalancesDto(
                listOf(
                    CurrencyBalancesDto("EUR", listOf(person("p1", "TJ", 100))),
                    CurrencyBalancesDto("USD", listOf(person("p1", "TJ", 900))),
                ),
            ),
        )
        val c = controller()
        c.open("h1")
        advanceUntilIdle()

        assertEquals(900, c.state.value.balances.single().netCents)
        assertTrue(c.state.value.otherCurrencies)
    }
}
