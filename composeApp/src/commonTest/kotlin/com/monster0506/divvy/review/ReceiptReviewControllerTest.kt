package com.monster0506.divvy.review

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.AcceptedPredictionsDto
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.ConfirmedReceiptDto
import com.monster0506.divvy.data.dto.EditedItemDto
import com.monster0506.divvy.data.dto.EditedOwnerDto
import com.monster0506.divvy.data.dto.EditedReceiptDto
import com.monster0506.divvy.data.dto.ItemEditRequest
import com.monster0506.divvy.data.dto.ReconciliationDto
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.ItemOwnerReadDto
import com.monster0506.divvy.data.dto.ItemOwnersDto
import com.monster0506.divvy.data.dto.ItemPredictionDto
import com.monster0506.divvy.data.dto.MoneyDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.PredictionsDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptItemDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.data.dto.ReceiptUploadDto
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun person(id: String, name: String, archived: Boolean = false) =
    PersonDto(id, "h1", name, archivedAt = if (archived) "2026-10-01T00:00:00Z" else null)

private fun item(id: String, line: Int, name: String, cents: Long, vararg owners: ItemOwnerReadDto) =
    ReceiptItemDto(id, name, MoneyDto(cents), lineNumber = line, rawText = name.uppercase(), owners = owners.toList())

private fun guess(person: String, confidence: Double) =
    ItemOwnerReadDto(person, source = "model", confidence = confidence)

private fun receipt(
    status: ReceiptStatusDto = ReceiptStatusDto.Draft,
    payer: String? = null,
    reason: String? = null,
    items: List<ReceiptItemDto> = listOf(
        item("i1", 1, "bananas", 188, guess("p1", 0.95)),
        item("i2", 2, "great value milk", 398, guess("p2", 0.6)),
        item("i3", 3, "mystery thing", 250),
    ),
) = ReceiptDto(
    id = "r1",
    householdId = "h1",
    status = status,
    paidByPersonId = payer,
    merchantName = "Walmart",
    purchasedAt = "2026-09-18T12:00:00Z",
    tax = MoneyDto(41),
    total = MoneyDto(877),
    items = items,
    failureReason = reason,
)

private class FakeApi : ReviewApi {
    var polls = mutableListOf<ApiResult<ReceiptDto>>()
    var lastPoll: ApiResult<ReceiptDto> = ApiResult.Success(receipt())
    var people: ApiResult<List<PersonDto>> = ApiResult.Success(listOf(person("p1", "TJ"), person("p2", "Sumedh")))
    var predictions: ApiResult<PredictionsDto> = ApiResult.Success(
        PredictionsDto(
            "r1",
            items = listOf(
                ItemPredictionDto("i1", "predicted", listOf("p1"), 0.95),
                ItemPredictionDto("i2", "predicted", listOf("p2"), 0.6, needsConfirmation = true),
                ItemPredictionDto("i3", "unknown"),
            ),
        ),
    )
    var payerResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var ownersResult: ApiResult<ItemOwnersDto> = ApiResult.Success(ItemOwnersDto("i1"))
    var acceptResult: ApiResult<AcceptedPredictionsDto> = ApiResult.Success(AcceptedPredictionsDto())
    var confirmResult: ApiResult<ConfirmedReceiptDto> =
        ApiResult.Success(ConfirmedReceiptDto("r1", ReceiptStatusDto.Confirmed))
    var retryResult: ApiResult<ReceiptUploadDto> = ApiResult.Success(ReceiptUploadDto("r1", "processing"))

    val calls = mutableListOf<String>()
    var pollCount = 0

    override suspend fun receipt(id: String): ApiResult<ReceiptDto> {
        pollCount++
        return if (polls.isNotEmpty()) polls.removeAt(0) else lastPoll
    }

    override suspend fun people(householdId: String) = people

    override suspend fun predictions(householdId: String, receiptId: String) = predictions

    override suspend fun setPayer(receiptId: String, personId: String): ApiResult<Unit> {
        calls += "payer:$personId"
        return payerResult
    }

    override suspend fun setOwners(
        receiptId: String,
        itemId: String,
        owners: List<ItemOwnerDto>
    ): ApiResult<ItemOwnersDto> {
        calls += "owners:$itemId:${owners.map { it.personId }}"
        return ownersResult
    }

    override suspend fun accept(
        householdId: String,
        receiptId: String,
        itemIds: List<String>?
    ): ApiResult<AcceptedPredictionsDto> {
        calls += "accept:$itemIds"
        return acceptResult
    }

    override suspend fun confirm(receiptId: String): ApiResult<ConfirmedReceiptDto> {
        calls += "confirm"
        return confirmResult
    }

    override suspend fun retry(householdId: String, receiptId: String): ApiResult<ReceiptUploadDto> {
        calls += "retry"
        return retryResult
    }

    var rerunResult: ApiResult<PredictionsDto> = ApiResult.Success(PredictionsDto("r1"))

    override suspend fun rerunPredictions(householdId: String, receiptId: String): ApiResult<PredictionsDto> {
        calls += "rerun"
        return rerunResult
    }

    var editResult: ApiResult<EditedReceiptDto> = ApiResult.Success(edited())

    override suspend fun addItem(receiptId: String, item: ItemEditRequest): ApiResult<EditedReceiptDto> {
        calls += "add:${item.description}:${item.totalMinor}"
        return editResult
    }

    override suspend fun updateItem(
        receiptId: String,
        itemId: String,
        change: ItemEditRequest
    ): ApiResult<EditedReceiptDto> {
        calls += "update:$itemId:${change.description}:${change.totalMinor}"
        return editResult
    }

    override suspend fun deleteItem(receiptId: String, itemId: String): ApiResult<EditedReceiptDto> {
        calls += "delete:$itemId"
        return editResult
    }

    override suspend fun mergeItems(receiptId: String, itemIds: List<String>): ApiResult<EditedReceiptDto> {
        calls += "merge:$itemIds"
        return editResult
    }
}

private fun edited(
    vararg items: EditedItemDto,
    reconciled: Boolean = true,
    subtotalGap: Long? = null,
) = EditedReceiptDto(
    id = "r1",
    householdId = "h1",
    status = ReceiptStatusDto.Draft,
    items = items.toList(),
    taxMinor = 41,
    reconciliation = ReconciliationDto(items.sumOf { it.totalMinor }, 0, subtotalGap, reconciled),
)

private fun line(id: String, number: Int, name: String, cents: Long, vararg owners: String) =
    EditedItemDto(id, name, cents, lineNumber = number, owners = owners.map { EditedOwnerDto(it) })

private fun failure(f: ApiFailure) = ApiResult.Failure(f)

class ReceiptReviewControllerTest {
    private val remembered = mutableListOf<String>()

    private fun TestScope.controller(api: FakeApi, readTimeout: Long = 60_000) =
        ReceiptReviewController(
            api, this, pollMillis = 1_000, readTimeoutMillis = readTimeout, maxMissedPolls = 3,
            onPayerChosen = { remembered += it },
        )

    private suspend fun TestScope.ready(api: FakeApi): ReceiptReviewController =
        controller(api).also {
            it.open("h1", "r1", payerId = "p1")
            advanceUntilIdle()
            assertEquals(ReviewPhase.Ready, it.state.value.phase, it.state.value.toString())
        }

    // --- waiting for the read -------------------------------------------------------------------

    @Test
    fun it_waits_while_the_server_reads_the_photo_then_shows_the_draft() = runTest {
        val api = FakeApi().apply {
            polls = mutableListOf(
                ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())),
                ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())),
            )
        }
        val c = controller(api)

        c.open("h1", "r1")
        assertEquals(ReviewPhase.Reading, c.state.value.phase)
        advanceUntilIdle()

        assertEquals(ReviewPhase.Ready, c.state.value.phase)
        assertEquals(3, api.pollCount)
        assertEquals("Walmart", c.state.value.draft?.store)
        assertEquals("Sep 18, 2026", c.state.value.draft?.date)
        assertEquals(41, c.state.value.draft?.taxCents)
        assertEquals(
            listOf("bananas", "great value milk", "mystery thing"),
            c.state.value.draft?.lines?.map { it.name })
        assertEquals(listOf(188, 398, 250), c.state.value.draft?.lines?.map { it.priceCents })
    }

    @Test
    fun the_servers_confidence_decides_which_lines_need_confirming() = runTest {
        val c = ready(FakeApi())

        val lines = c.state.value.draft!!.lines
        assertEquals(listOf(true, false, false), lines.map { it.confirmed })
        assertEquals(0.95f, lines[0].confidence)
        assertEquals(0.6f, lines[1].confidence)
        assertEquals(0f, lines[2].confidence)
        assertEquals(setOf("p2"), lines[1].owners)
    }

    @Test
    fun without_predictions_every_guessed_line_is_still_shown_with_its_owners() = runTest {
        val api = FakeApi().apply { predictions = failure(ApiFailure.Offline) }

        val c = ready(api)

        assertEquals(listOf(true, true, false), c.state.value.draft!!.lines.map { it.confirmed })
    }

    @Test
    fun a_failed_read_shows_the_servers_reason() = runTest {
        val api = FakeApi().apply {
            lastPoll = ApiResult.Success(
                receipt(
                    ReceiptStatusDto.Failed,
                    reason = "No items could be read from the photo.",
                    items = emptyList()
                )
            )
        }
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Failed, c.state.value.phase)
        assertEquals("No items could be read from the photo.", c.state.value.message)
    }

    @Test
    fun retry_asks_the_server_to_read_again_and_follows_it() = runTest {
        val api = FakeApi().apply {
            lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Failed, reason = "x", items = emptyList()))
        }
        val c = controller(api)
        c.open("h1", "r1")
        advanceUntilIdle()

        api.lastPoll = ApiResult.Success(receipt())
        c.retry()
        advanceUntilIdle()

        assertTrue("retry" in api.calls)
        assertEquals(ReviewPhase.Ready, c.state.value.phase)
        assertNull(c.state.value.message)
    }

    @Test
    fun a_server_that_cannot_be_reached_is_tried_a_few_times_then_reported() = runTest {
        val api = FakeApi().apply { lastPoll = failure(ApiFailure.Offline) }
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Unreachable, c.state.value.phase)
        assertEquals(3, api.pollCount)
        assertEquals("Could not reach the server.", c.state.value.message)
    }

    @Test
    fun a_blip_in_the_connection_does_not_stop_the_wait() = runTest {
        val api = FakeApi().apply { polls = mutableListOf(failure(ApiFailure.Offline), failure(ApiFailure.Offline)) }
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Ready, c.state.value.phase)
    }

    @Test
    fun a_rejected_key_or_a_missing_receipt_stops_at_once() = runTest {
        val api = FakeApi().apply { lastPoll = failure(ApiFailure.Unauthorised) }
        val c = controller(api)
        c.open("h1", "r1")
        advanceUntilIdle()
        assertEquals(1, api.pollCount)
        assertEquals(ReviewPhase.Unreachable, c.state.value.phase)

        val missing =
            FakeApi().apply { lastPoll = failure(ApiFailure.Validation(ApiError("not_found", "receipt not found"))) }
        val d = controller(missing)
        d.open("h1", "r1")
        advanceUntilIdle()
        assertEquals(1, missing.pollCount)
        assertEquals("receipt not found", d.state.value.message)
    }

    @Test
    fun a_read_that_never_finishes_gives_up_with_a_message() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 5_000)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Failed, c.state.value.phase)
        assertEquals(5, api.pollCount)
        assertTrue(c.state.value.message!!.contains("taking longer"))
    }

    @Test
    fun an_already_confirmed_receipt_is_shown_as_confirmed() = runTest {
        val api = FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Confirmed, payer = "p1")) }
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Confirmed, c.state.value.phase)
    }

    @Test
    fun a_household_with_nobody_in_it_cannot_be_reviewed() = runTest {
        val api = FakeApi().apply { people = ApiResult.Success(listOf(person("p1", "TJ", archived = true))) }
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(ReviewPhase.Unreachable, c.state.value.phase)
        assertEquals(0, api.pollCount)
    }

    // --- who paid ---------------------------------------------------------------------------------

    @Test
    fun a_receipt_without_a_payer_gets_the_chosen_one_saved() = runTest {
        val api = FakeApi()
        val c = controller(api)

        c.open("h1", "r1", payerId = "p2")
        advanceUntilIdle()

        assertEquals(listOf("payer:p2"), api.calls)
        assertEquals("Sumedh", c.state.value.draft?.payer?.name)
    }

    @Test
    fun nobody_is_saved_as_payer_until_one_is_chosen() = runTest {
        val api = FakeApi()
        val c = controller(api)

        c.open("h1", "r1")
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertFalse(c.state.value.draft!!.payerChosen)

        val unknown = FakeApi()
        controller(unknown).also { it.open("h1", "r1", payerId = "someone-else") }
        advanceUntilIdle()
        assertTrue(unknown.calls.isEmpty())
    }

    @Test
    fun confirming_without_a_chosen_payer_is_refused() = runTest {
        val api = FakeApi()
        val c = controller(api).also { it.open("h1", "r1") }
        advanceUntilIdle()

        c.confirm()
        advanceUntilIdle()

        assertFalse("confirm" in api.calls)
        assertEquals("Choose who paid before confirming.", c.state.value.message)
    }

    @Test
    fun choosing_a_payer_saves_it_remembers_it_and_unblocks_confirm() = runTest {
        val api = FakeApi()
        val c = controller(api).also { it.open("h1", "r1") }
        advanceUntilIdle()

        c.setPayer("p2")
        assertEquals("Sumedh", c.state.value.draft?.payer?.name)
        assertTrue(c.state.value.draft!!.payerChosen)
        advanceUntilIdle()
        assertEquals(listOf("payer:p2"), api.calls)
        assertEquals(listOf("p2"), remembered)

        c.confirm()
        advanceUntilIdle()
        assertEquals(ReviewPhase.Confirmed, c.state.value.phase)
    }

    @Test
    fun a_payer_the_server_refuses_is_put_back() = runTest {
        val api = FakeApi().apply { payerResult = failure(ApiFailure.Offline) }
        val c = controller(api).also { it.open("h1", "r1") }
        advanceUntilIdle()

        c.setPayer("p2")
        advanceUntilIdle()

        assertFalse(c.state.value.draft!!.payerChosen)
        assertTrue(remembered.isEmpty())
        assertTrue(c.state.value.message!!.startsWith("Could not save who paid"))
    }

    @Test
    fun a_chosen_payer_can_be_changed() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.setPayer("p2")
        advanceUntilIdle()

        assertEquals(listOf("payer:p1", "payer:p2"), api.calls)
        assertEquals("Sumedh", c.state.value.draft?.payer?.name)
    }

    @Test
    fun a_payer_the_server_already_has_is_kept_and_not_saved_again() = runTest {
        val api = FakeApi().apply { lastPoll = ApiResult.Success(receipt(payer = "p2")) }
        val c = controller(api)

        c.open("h1", "r1", payerId = "p1")
        advanceUntilIdle()

        assertTrue(api.calls.isEmpty())
        assertEquals("Sumedh", c.state.value.draft?.payer?.name)
    }

    @Test
    fun confirming_is_blocked_until_the_payer_is_saved() = runTest {
        val api = FakeApi().apply { payerResult = failure(ApiFailure.Offline) }
        val c = ready(api)
        assertTrue(c.state.value.message!!.startsWith("Could not save who paid"))

        c.confirm()
        advanceUntilIdle()

        assertFalse("confirm" in api.calls)
        assertEquals("Choose who paid before confirming.", c.state.value.message)
        assertEquals(ReviewPhase.Ready, c.state.value.phase)
    }

    // --- assigning ------------------------------------------------------------------------------

    @Test
    fun choosing_owners_shows_at_once_and_saves_to_the_server() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.setOwners(3, setOf("p1", "p2"))
        assertEquals(setOf("p1", "p2"), c.state.value.draft!!.lines[2].owners)
        assertTrue(c.state.value.draft!!.lines[2].confirmed)
        advanceUntilIdle()

        assertEquals("owners:i3:[p1, p2]", api.calls.last())
    }

    @Test
    fun toggling_an_owner_adds_and_removes_them() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.toggleOwner(2, "p1")
        advanceUntilIdle()
        assertEquals(setOf("p2", "p1"), c.state.value.draft!!.lines[1].owners)

        c.toggleOwner(2, "p2")
        advanceUntilIdle()
        assertEquals(setOf("p1"), c.state.value.draft!!.lines[1].owners)
        assertEquals("owners:i2:[p1]", api.calls.last())
    }

    @Test
    fun a_change_the_server_refuses_is_put_back_with_a_message() = runTest {
        val api = FakeApi().apply {
            ownersResult = failure(ApiFailure.Validation(ApiError("conflict", "receipt is confirmed")))
        }
        val c = ready(api)
        val before = c.state.value.draft!!.lines[1]

        c.setOwners(2, setOf("p1"))
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft!!.lines[1])
        assertTrue(c.state.value.message!!.contains("Could not save that change"))
        assertTrue(c.state.value.message!!.contains("receipt is confirmed"))
    }

    @Test
    fun clearing_every_owner_leaves_the_line_unassigned() = runTest {
        val c = ready(FakeApi())

        c.setOwners(1, emptySet())
        advanceUntilIdle()

        val line = c.state.value.draft!!.lines[0]
        assertFalse(line.confirmed)
        assertEquals(0f, line.confidence)
    }

    @Test
    fun accepting_a_guess_confirms_that_line_on_the_server() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.accept(2)
        assertTrue(c.state.value.draft!!.lines[1].confirmed)
        advanceUntilIdle()

        assertEquals("accept:[i2]", api.calls.last())
    }

    @Test
    fun a_refused_accept_unconfirms_the_line_again() = runTest {
        val api = FakeApi().apply { acceptResult = failure(ApiFailure.Offline) }
        val c = ready(api)

        c.accept(2)
        advanceUntilIdle()

        assertFalse(c.state.value.draft!!.lines[1].confirmed)
        assertTrue(c.state.value.message!!.startsWith("Could not confirm that item"))
    }

    @Test
    fun a_line_the_controller_does_not_know_is_ignored() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.setOwners(99, setOf("p1"))
        c.accept(99)
        c.toggleOwner(99, "p1")
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    // --- confirming -----------------------------------------------------------------------------

    @Test
    fun confirming_moves_to_confirmed() = runTest {
        val api = FakeApi()
        val c = ready(api)

        c.confirm()
        assertEquals(ReviewPhase.Confirming, c.state.value.phase)
        advanceUntilIdle()

        assertEquals(ReviewPhase.Confirmed, c.state.value.phase)
        assertEquals("confirm", api.calls.last())
    }

    @Test
    fun unconfirmed_items_come_back_marked_and_the_receipt_stays_open() = runTest {
        val api = FakeApi().apply {
            confirmResult = failure(
                ApiFailure.Validation(
                    ApiError(
                        "unconfirmed_items",
                        "1 item(s) need to be confirmed first",
                        listOf("i1")
                    )
                ),
            )
        }
        val c = ready(api)
        assertTrue(c.state.value.draft!!.lines[0].confirmed)

        c.confirm()
        advanceUntilIdle()

        assertEquals(ReviewPhase.Ready, c.state.value.phase)
        assertEquals("1 item(s) need to be confirmed first", c.state.value.message)
        assertFalse(c.state.value.draft!!.lines[0].confirmed)
    }

    @Test
    fun confirming_only_works_from_the_review_screen() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")

        c.confirm()

        assertTrue(api.calls.isEmpty())
        c.close()
    }

    @Test
    fun closing_clears_the_state_and_stops_waiting() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")
        advanceUntilIdle()
        val polls = api.pollCount

        c.close()
        advanceUntilIdle()

        assertEquals(ReviewPhase.Idle, c.state.value.phase)
        assertNotNull(c.state.value)
        assertEquals(polls, api.pollCount)
    }

    @Test
    fun opening_another_receipt_replaces_the_first() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")
        advanceUntilIdle()

        api.lastPoll = ApiResult.Success(receipt())
        c.open("h1", "r2")
        advanceUntilIdle()

        assertEquals("r2", c.state.value.receiptId)
        assertEquals(ReviewPhase.Ready, c.state.value.phase)
    }

    // --- editing lines ----------------------------------------------------------------------------

    @Test
    fun editing_a_line_saves_it_and_shows_the_servers_answer_keeping_the_other_lines_state() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i2", 2, "whole milk", 410, "p2"),
                line("i3", 3, "mystery thing", 250),
            ),
        )

        c.editLine(2, "  whole milk ", 410)
        advanceUntilIdle()

        assertEquals("update:i2:whole milk:410", api.calls.last())
        val lines = c.state.value.draft!!.lines
        assertEquals(listOf("bananas", "whole milk", "mystery thing"), lines.map { it.name })
        assertEquals(410, lines[1].priceCents)
        assertEquals(listOf(true, false, false), lines.map { it.confirmed })
        assertEquals(0.95f, lines[0].confidence)
        assertNull(c.state.value.message)
    }

    @Test
    fun an_edit_the_server_refuses_leaves_the_lines_alone_and_says_why() = runTest {
        val api = FakeApi().apply {
            editResult =
                failure(ApiFailure.Validation(ApiError("conflict", "receipt is confirmed and can no longer be edited")))
        }
        val c = ready(api)
        val before = c.state.value.draft

        c.editLine(1, "x", 1)
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft)
        assertTrue(c.state.value.message!!.contains("can no longer be edited"))
    }

    @Test
    fun a_new_line_starts_with_no_owner_and_needs_confirming() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i2", 2, "great value milk", 398, "p2"),
                line("i3", 3, "mystery thing", 250),
                line("i4", 4, "paper towels", 899),
            ),
        )

        c.addLine("paper towels", 899)
        advanceUntilIdle()

        assertEquals("add:paper towels:899", api.calls.last())
        val added = c.state.value.draft!!.lines.last()
        assertEquals("paper towels", added.name)
        assertEquals(emptySet(), added.owners)
        assertFalse(added.confirmed)
    }

    @Test
    fun deleting_a_line_removes_it_and_undo_puts_it_back() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult = ApiResult.Success(
            edited(line("i1", 1, "bananas", 188, "p1"), line("i3", 2, "mystery thing", 250)),
        )

        c.deleteLine(2)
        advanceUntilIdle()

        assertEquals("delete:i2", api.calls.last())
        assertEquals(listOf("bananas", "mystery thing"), c.state.value.draft!!.lines.map { it.name })
        assertTrue(c.state.value.canUndo)

        api.editResult = ApiResult.Success(
            edited(
                line("i1", 1, "bananas", 188, "p1"),
                line("i3", 2, "mystery thing", 250),
                line("i5", 3, "great value milk", 398),
            ),
        )
        c.undoDelete()
        advanceUntilIdle()

        assertEquals("add:great value milk:398", api.calls.last())
        assertFalse(c.state.value.canUndo)
        assertEquals(3, c.state.value.draft!!.lines.size)
    }

    @Test
    fun undo_with_nothing_deleted_does_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.undoDelete()
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    @Test
    fun a_delete_the_server_refuses_cannot_be_undone() = runTest {
        val api = FakeApi().apply { editResult = failure(ApiFailure.Offline) }
        val c = ready(api)

        c.deleteLine(2)
        advanceUntilIdle()

        assertFalse(c.state.value.canUndo)
        assertEquals(3, c.state.value.draft!!.lines.size)
    }

    @Test
    fun merging_lines_sends_their_items_in_order_and_shows_the_combined_line() = runTest {
        val api = FakeApi()
        val c = ready(api)
        api.editResult =
            ApiResult.Success(edited(line("i1", 1, "bananas", 188, "p1"), line("i3", 2, "mystery thing", 250)))

        c.mergeLines(setOf(3, 2))
        advanceUntilIdle()

        assertEquals("merge:[i2, i3]", api.calls.last())
        assertEquals(2, c.state.value.draft!!.lines.size)
    }

    @Test
    fun merging_fewer_than_two_known_lines_does_nothing() = runTest {
        val api = FakeApi()
        val c = ready(api)
        val calls = api.calls.size

        c.mergeLines(setOf(2))
        c.mergeLines(setOf(2, 99))
        advanceUntilIdle()

        assertEquals(calls, api.calls.size)
    }

    @Test
    fun edits_are_ignored_before_the_draft_is_ready_and_for_unknown_lines() = runTest {
        val api =
            FakeApi().apply { lastPoll = ApiResult.Success(receipt(ReceiptStatusDto.Processing, items = emptyList())) }
        val c = controller(api, readTimeout = 100_000)
        c.open("h1", "r1")

        c.addLine("x", 1)
        advanceUntilIdle()
        assertTrue(api.calls.none { it.startsWith("add") })
        c.close()

        val ready = FakeApi()
        val d = ready(ready)
        val calls = ready.calls.size
        d.editLine(99, "x", 1)
        d.deleteLine(99)
        advanceUntilIdle()
        assertEquals(calls, ready.calls.size)
    }

    // --- the lines against the printed amounts --------------------------------------------------

    @Test
    fun a_draft_whose_lines_do_not_match_the_printed_subtotal_shows_a_warning() = runTest {
        val api = FakeApi().apply {
            lastPoll =
                ApiResult.Success(receipt().copy(subtotal = MoneyDto(1000), tax = MoneyDto(41), total = MoneyDto(1041)))
        }

        val c = ready(api)

        assertEquals(
            "The lines add up to \$8.36, which is \$1.64 less than the receipt subtotal.",
            c.state.value.warning,
        )
    }

    @Test
    fun a_draft_that_adds_up_has_no_warning_and_an_edit_can_clear_or_raise_one() = runTest {
        val api = FakeApi().apply {
            lastPoll =
                ApiResult.Success(receipt().copy(subtotal = MoneyDto(836), tax = MoneyDto(41), total = MoneyDto(877)))
        }
        val c = ready(api)
        assertNull(c.state.value.warning)

        api.editResult = ApiResult.Success(
            edited(line("i1", 1, "bananas", 188, "p1"), reconciled = false, subtotalGap = -20),
        )
        c.editLine(1, "bananas", 188)
        advanceUntilIdle()
        assertEquals(
            "The lines add up to \$1.88, which is \$0.20 more than the receipt subtotal.",
            c.state.value.warning,
        )

        api.editResult = ApiResult.Success(edited(line("i1", 1, "bananas", 188, "p1")))
        c.editLine(1, "bananas", 188)
        advanceUntilIdle()
        assertNull(c.state.value.warning)
    }

    @Test
    fun the_total_is_used_when_there_is_no_subtotal() {
        val dto = receipt().copy(subtotal = null, tax = MoneyDto(41), total = MoneyDto(900))

        val warning = reconciliationWarning(reconcile(dto))

        assertEquals("The lines add up to \$8.36, which is \$0.23 less than the receipt total.", warning)
    }

    @Test
    fun a_receipt_with_no_printed_amounts_is_never_flagged() {
        val dto = receipt().copy(subtotal = null, tax = null, total = null)

        assertNull(reconciliationWarning(reconcile(dto)))
    }

    // --- mapping ----------------------------------------------------------------------------------

    @Test
    fun dates_are_shown_as_month_day_year_and_odd_input_is_left_alone() {
        assertEquals("Sep 18, 2026", formatDate("2026-09-18T12:00:00Z"))
        assertEquals("Jan 1, 2027", formatDate("2027-01-01"))
        assertEquals("sometime", formatDate("sometime"))
        assertEquals("2026-13-01", formatDate("2026-13-01"))
    }

    @Test
    fun lines_without_a_line_number_fall_back_to_their_position() {
        val dto = receipt(items = listOf(item("a", 0, "x", 100), item("b", 0, "y", 200)))
        val people = listOf(person("p1", "TJ").toUiPerson(0))

        val lines = buildReviewLines(dto, people, people[0], null)

        assertEquals(listOf(1, 2), lines.draft.lines.map { it.id })
        assertEquals(mapOf(1 to "a", 2 to "b"), lines.itemIds)
    }

    @Test
    fun a_line_a_person_chose_has_full_confidence_and_a_mix_takes_the_least_sure_guess() {
        val dto = receipt(
            items = listOf(
                item("a", 1, "chosen", 100, ItemOwnerReadDto("p1")),
                item("b", 2, "two guesses", 100, guess("p1", 0.9), guess("p2", 0.7)),
                item("c", 3, "mixed", 100, ItemOwnerReadDto("p1"), guess("p2", 0.2)),
            ),
        )
        val people = listOf(person("p1", "TJ").toUiPerson(0), person("p2", "Sumedh").toUiPerson(1))

        val lines = buildReviewLines(dto, people, people[0], null).draft.lines

        assertEquals(listOf(1f, 0.7f, 1f), lines.map { it.confidence })
    }

    @Test
    fun people_get_initials_and_distinct_colours() {
        val ui = listOf(person("p1", "TJ"), person("p2", "Mary Jane")).mapIndexed { i, p -> p.toUiPerson(i) }

        assertEquals(listOf("TJ", "MJ"), ui.map { it.initials })
        assertTrue(ui[0].color != ui[1].color)
    }
}
