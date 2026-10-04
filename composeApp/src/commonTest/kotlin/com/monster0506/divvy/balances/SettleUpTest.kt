package com.monster0506.divvy.balances

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.CreateSettlementRequest
import com.monster0506.divvy.data.dto.CurrencyBalancesDto
import com.monster0506.divvy.data.dto.PersonBalanceDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.SettlementDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun person(id: String, name: String, net: Long) = PersonBalanceDto(id, name, net)

private fun usd(vararg people: PersonBalanceDto) = BalancesDto(listOf(CurrencyBalancesDto("USD", people.toList())))

/** A household where TJ is owed 20.00, Sam owes 12.00 and Ana owes 8.00. */
private val household = usd(person("tj", "TJ", 2000), person("sam", "Sam", -1200), person("ana", "Ana", -800))

private class FakeServer {
    var balances: BalancesDto = household
    var settlementFailure: ApiFailure? = null
    val settlements = mutableListOf<CreateSettlementRequest>()
    var venmoFailure: ApiFailure? = null
    val venmoSaves = mutableListOf<Pair<String, String>>()

    suspend fun saveVenmo(householdId: String, personId: String, username: String): ApiResult<PersonDto> {
        venmoSaves += personId to username
        venmoFailure?.let { return ApiResult.Failure(it) }
        return ApiResult.Success(PersonDto(personId, householdId, personId, venmoUsername = username.ifEmpty { null }))
    }

    suspend fun load(id: String): ApiResult<BalancesDto> = ApiResult.Success(balances)

    suspend fun record(id: String, request: CreateSettlementRequest): ApiResult<SettlementDto> {
        settlements += request
        settlementFailure?.let { return ApiResult.Failure(it) }
        // like the server: a payment moves the balances the other way
        balances = BalancesDto(
            balances.balances.map { c ->
                c.copy(
                    people = c.people.map { p ->
                        when (p.personId) {
                            request.fromPersonId -> p.copy(netMinor = p.netMinor + request.amountMinor)
                            request.toPersonId -> p.copy(netMinor = p.netMinor - request.amountMinor)
                            else -> p
                        }
                    },
                )
            },
        )
        return ApiResult.Success(SettlementDto("s${settlements.size}", id, request.fromPersonId, request.toPersonId, request.amountMinor))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettleUpTest {
    private val server = FakeServer()

    private fun TestScope.controller(me: String? = null, window: Long = 8_000) = BalancesController(
        load = server::load,
        scope = this,
        record = server::record,
        currentPerson = { me },
        undoWindowMillis = window,
        saveVenmo = server::saveVenmo,
    )

    private suspend fun TestScope.open(c: BalancesController): BalancesController {
        c.open("h1")
        advanceUntilIdle()
        return c
    }

    // --- the suggested payments -----------------------------------------------------------------

    @Test
    fun the_fewest_payments_that_settle_everyone_are_suggested() = runTest {
        val c = open(controller())

        val transfers = c.state.value.transfers
        assertEquals(listOf("Sam" to 1200, "Ana" to 800), transfers.map { it.from.name to it.amountCents })
        assertTrue(transfers.all { it.to.name == "TJ" })
    }

    @Test
    fun a_settled_household_has_no_payments() = runTest {
        server.balances = usd(person("tj", "TJ", 0), person("sam", "Sam", 0))

        val c = open(controller())

        assertTrue(c.state.value.transfers.isEmpty())
        assertTrue(c.state.value.settled)
    }

    @Test
    fun payments_carry_the_recipients_venmo_username_when_they_have_one() = runTest {
        server.balances = usd(
            person("tj", "TJ", 2000).copy(venmoUsername = "tj-pays"),
            person("sam", "Sam", -1200),
            person("ana", "Ana", -800).copy(venmoUsername = "ana-v"),
        )

        val c = open(controller())

        assertEquals(listOf("tj-pays", "tj-pays"), c.state.value.transfers.map { it.toVenmo })
    }

    @Test
    fun payments_to_someone_without_venmo_have_no_username() = runTest {
        server.balances = usd(
            person("tj", "TJ", 2000).copy(venmoUsername = " "),
            person("sam", "Sam", -2000).copy(venmoUsername = "sam-v"),
        )

        val c = open(controller())

        assertNull(c.state.value.transfers.single().toVenmo)
    }

    @Test
    fun venmo_is_offered_only_on_payments_this_devices_person_makes() = runTest {
        val transfers = open(controller()).state.value.transfers

        // Ana pays TJ: Ana sees Venmo on her own payment only.
        assertEquals(listOf("Ana"), transfers.filter { it.isPaidBy("ana") }.map { it.from.name })
        // TJ is being paid, so has nothing to pay with Venmo.
        assertTrue(transfers.none { it.isPaidBy("tj") })
        // Unknown person: no Venmo at all.
        assertTrue(transfers.none { it.isPaidBy(null) })
    }

    @Test
    fun a_venmo_username_typed_on_settle_up_is_saved_and_used_for_their_payments() = runTest {
        val c = open(controller())
        val tj = c.state.value.transfers.first().to
        var saved = false

        c.setVenmo(tj, " @tj-pays ") { saved = true }
        advanceUntilIdle()

        assertEquals(listOf("tj" to "tj-pays"), server.venmoSaves)
        assertTrue(saved)
        assertEquals(listOf("tj-pays", "tj-pays"), c.state.value.transfers.map { it.toVenmo })
        assertFalse(c.state.value.savingVenmo)
        assertNull(c.state.value.venmoMessage)
    }

    @Test
    fun an_invalid_venmo_username_is_not_sent() = runTest {
        val c = open(controller())
        var saved = false

        c.setVenmo(c.state.value.transfers.first().to, "tj") { saved = true }
        advanceUntilIdle()

        assertTrue(server.venmoSaves.isEmpty())
        assertFalse(saved)
        assertEquals("Venmo usernames are at least 5 characters.", c.state.value.venmoMessage)
    }

    @Test
    fun a_rejected_venmo_username_shows_the_servers_message_and_changes_nothing() = runTest {
        server.venmoFailure = ApiFailure.Validation(ApiError("validation_error", "venmoUsername must be 5 to 30 letters"))
        val c = open(controller())
        var saved = false

        c.setVenmo(c.state.value.transfers.first().to, "tj-pays") { saved = true }
        advanceUntilIdle()

        assertFalse(saved)
        assertEquals("venmoUsername must be 5 to 30 letters", c.state.value.venmoMessage)
        assertTrue(c.state.value.transfers.all { it.toVenmo == null })
        c.dismissVenmoMessage()
        assertNull(c.state.value.venmoMessage)
    }

    @Test
    fun payments_involving_this_devices_person_come_first_and_are_marked() = runTest {
        val c = open(controller(me = "ana"))

        val transfers = c.state.value.transfers
        assertEquals(listOf("Ana", "Sam"), transfers.map { it.from.name })
        assertEquals(listOf(true, false), transfers.map { it.involvesMe })
    }

    @Test
    fun balances_that_do_not_add_up_give_no_suggestions_instead_of_wrong_ones() = runTest {
        server.balances = usd(person("tj", "TJ", 2000), person("sam", "Sam", -1200))

        val c = open(controller())

        assertTrue(c.state.value.transfers.isEmpty())
    }

    @Test
    fun a_payment_to_someone_not_in_the_list_is_dropped_not_crashed() = runTest {
        server.balances = BalancesDto(listOf(CurrencyBalancesDto("USD", listOf(person("tj", "TJ", 500), person("sam", "Sam", -500)))))

        val c = open(controller())

        assertEquals(1, c.state.value.transfers.size)
    }

    // --- recording a payment --------------------------------------------------------------------

    @Test
    fun marking_a_payment_paid_records_it_and_refreshes_the_balances() = runTest {
        val c = open(controller())
        val sam = c.state.value.transfers.first { it.from.name == "Sam" }

        c.settle(sam)
        advanceUntilIdle()

        assertEquals(listOf(CreateSettlementRequest("sam", "tj", 1200)), server.settlements)
        val state = c.state.value
        assertEquals(listOf("Ana"), state.transfers.map { it.from.name })
        assertEquals(0, state.balances.first { it.person.name == "Sam" }.netCents)
        assertFalse(state.recording)
    }

    @Test
    fun a_part_payment_records_the_smaller_amount_and_leaves_the_rest_owed() = runTest {
        val c = open(controller())
        val sam = c.state.value.transfers.first { it.from.name == "Sam" }

        c.settle(sam, 500)
        advanceUntilIdle()

        assertEquals(500L, server.settlements.single().amountMinor)
        assertEquals(700, c.state.value.transfers.first { it.from.name == "Sam" }.amountCents)
    }

    @Test
    fun the_last_payment_is_offered_for_undo() = runTest {
        val c = open(controller())

        c.settle(c.state.value.transfers.first { it.from.name == "Ana" })
        runCurrent()

        val payment = assertNotNull(c.state.value.payment)
        assertEquals(Triple("Ana", "TJ", 800), Triple(payment.from.name, payment.to.name, payment.amountCents))
    }

    @Test
    fun an_amount_of_zero_or_more_than_is_owed_is_refused_and_nothing_is_sent() = runTest {
        val c = open(controller())
        val sam = c.state.value.transfers.first { it.from.name == "Sam" }

        c.settle(sam, 0)
        c.settle(sam, 1201)
        advanceUntilIdle()

        assertTrue(server.settlements.isEmpty())
        assertEquals("Enter an amount between \$0.01 and \$12.00.", c.state.value.message)
    }

    @Test
    fun a_payment_the_server_refuses_says_why_and_changes_nothing() = runTest {
        server.settlementFailure = ApiFailure.Validation(ApiError("validation_error", "toPersonId is not a member of this household"))
        val c = open(controller())
        val before = c.state.value.transfers

        c.settle(before.first())
        advanceUntilIdle()

        assertEquals(before, c.state.value.transfers)
        assertNull(c.state.value.payment)
        assertTrue(c.state.value.message!!.contains("not a member of this household"))
        assertFalse(c.state.value.recording)
    }

    @Test
    fun an_unreachable_server_is_reported_when_recording() = runTest {
        server.settlementFailure = ApiFailure.Offline
        val c = open(controller())

        c.settle(c.state.value.transfers.first())
        advanceUntilIdle()

        assertTrue(c.state.value.message!!.startsWith("Could not record that payment."))
    }

    @Test
    fun a_second_payment_is_ignored_while_one_is_being_recorded() = runTest {
        val c = open(controller())
        val transfers = c.state.value.transfers

        c.settle(transfers[0])
        c.settle(transfers[1])
        advanceUntilIdle()

        assertEquals(1, server.settlements.size)
    }

    // --- undo -----------------------------------------------------------------------------------

    @Test
    fun undo_records_the_same_amount_back_the_other_way_and_restores_the_balances() = runTest {
        val c = open(controller())
        c.settle(c.state.value.transfers.first { it.from.name == "Sam" })
        runCurrent()

        c.undo()
        runCurrent()

        assertEquals(CreateSettlementRequest("tj", "sam", 1200, note = "Undo"), server.settlements.last())
        val state = c.state.value
        assertNull(state.payment)
        assertEquals(listOf("Sam" to 1200, "Ana" to 800), state.transfers.map { it.from.name to it.amountCents })
    }

    @Test
    fun the_undo_offer_goes_away_after_the_window() = runTest {
        val c = open(controller(window = 5_000))
        c.settle(c.state.value.transfers.first())
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertNotNull(c.state.value.payment)

        advanceTimeBy(1_500)
        runCurrent()

        assertNull(c.state.value.payment)
    }

    @Test
    fun a_refused_undo_keeps_the_offer_and_says_why() = runTest {
        val c = open(controller())
        c.settle(c.state.value.transfers.first())
        runCurrent()
        server.settlementFailure = ApiFailure.Offline

        c.undo()
        runCurrent()

        assertNotNull(c.state.value.payment)
        assertTrue(c.state.value.message!!.startsWith("Could not undo that payment."))
    }

    @Test
    fun undo_with_nothing_to_undo_does_nothing() = runTest {
        val c = open(controller())

        c.undo()
        advanceUntilIdle()

        assertTrue(server.settlements.isEmpty())
    }

    @Test
    fun dismissing_the_banner_removes_the_undo_offer() = runTest {
        val c = open(controller())
        c.settle(c.state.value.transfers.first())
        runCurrent()

        c.dismissPayment()

        assertNull(c.state.value.payment)
    }

    @Test
    fun paying_everything_ends_all_settled_up() = runTest {
        val c = open(controller())

        c.state.value.transfers.forEach {
            c.settle(it)
            advanceUntilIdle()
        }

        assertTrue(c.state.value.settled)
        assertTrue(c.state.value.transfers.isEmpty())
    }

    @Test
    fun a_refresh_keeps_the_undo_offer_on_screen() = runTest {
        val c = open(controller())
        c.settle(c.state.value.transfers.first())
        runCurrent()

        c.refresh()
        runCurrent()

        assertNotNull(c.state.value.payment)
    }
}
