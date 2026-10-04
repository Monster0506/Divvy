package com.monster0506.divvy.history

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ApiError
import com.monster0506.divvy.data.dto.ItemOwnerReadDto
import com.monster0506.divvy.data.dto.MoneyDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptItemDto
import com.monster0506.divvy.data.dto.ReceiptPageDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.data.dto.ReceiptSummaryDto
import com.monster0506.divvy.domain.receiptShares
import com.monster0506.divvy.ui.model.ReceiptStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun person(id: String, name: String, archived: Boolean = false) =
    PersonDto(id, "h1", name, archivedAt = if (archived) "2026-10-01T00:00:00Z" else null)

private fun summary(
    id: String,
    status: ReceiptStatusDto,
    merchant: String? = "Walmart",
    payer: String? = "p1",
    total: Long = 877
) =
    ReceiptSummaryDto(id, "2026-09-18T12:00:00Z", "USD", total, status, 3, merchant, payer)

private fun item(id: String, cents: Long, vararg owners: Pair<String, Int>) = ReceiptItemDto(
    id = id,
    description = id,
    total = MoneyDto(cents),
    owners = owners.map { (person, weight) -> ItemOwnerReadDto(person, weight) },
)

private fun summaries(range: IntRange) = range.map { summary("r$it", ReceiptStatusDto.Confirmed) }

private class FakeBrowse : BrowseApi {
    var page: ApiResult<ReceiptPageDto> = ApiResult.Success(ReceiptPageDto())
    var detail: ApiResult<ReceiptDto> = ApiResult.Success(
        ReceiptDto(
            "r1",
            "h1",
            ReceiptStatusDto.Confirmed,
            paidByPersonId = "p2",
            merchantName = "Walmart",
            hasImage = true
        ),
    )
    var people: ApiResult<List<PersonDto>> = ApiResult.Success(listOf(person("p1", "TJ"), person("p2", "Sumedh")))
    var reopened: ApiResult<ReceiptDto> = ApiResult.Success(ReceiptDto("r1", "h1", ReceiptStatusDto.Draft))
    var photo: ApiResult<ByteArray> = ApiResult.Success(byteArrayOf(1, 2, 3))
    var listCalls = 0
    val queries = mutableListOf<ReceiptQuery>()
    var pages: (ReceiptQuery) -> ApiResult<ReceiptPageDto> = { page }

    override suspend fun receipts(householdId: String, query: ReceiptQuery): ApiResult<ReceiptPageDto> {
        listCalls++
        queries += query
        return pages(query)
    }

    override suspend fun receipt(id: String) = detail

    override suspend fun people(householdId: String) = people

    override suspend fun reopen(id: String) = reopened

    override suspend fun image(id: String) = photo
}

class ReceiptSharesTest {
    @Test
    fun an_item_is_split_by_weight_and_always_adds_up() {
        val shares = receiptShares(listOf(item("a", 1000, "p1" to 1, "p2" to 2)))

        assertEquals(mapOf("p1" to 333L, "p2" to 667L), shares)
        assertEquals(1000L, shares.values.sum())
    }

    @Test
    fun an_odd_cent_goes_to_the_largest_remainder_and_ties_to_the_lowest_id() {
        val shares = receiptShares(listOf(item("a", 100, "p2" to 1, "p1" to 1, "p3" to 1)))

        assertEquals(mapOf("p1" to 34L, "p2" to 33L, "p3" to 33L), shares)
    }

    @Test
    fun items_add_up_per_person_and_unowned_items_are_skipped() {
        val shares = receiptShares(
            listOf(item("a", 500, "p1" to 1), item("b", 300, "p1" to 1, "p2" to 1), item("c", 999)),
        )

        assertEquals(mapOf("p1" to 650L, "p2" to 150L), shares)
    }

    @Test
    fun a_refund_line_is_split_the_same_way() {
        val shares = receiptShares(listOf(item("a", -100, "p1" to 1, "p2" to 1)))

        assertEquals(-100L, shares.values.sum())
    }

    @Test
    fun nothing_owned_means_no_shares() {
        assertTrue(receiptShares(listOf(item("a", 100))).isEmpty())
        assertTrue(receiptShares(emptyList()).isEmpty())
    }
}

class ReceiptHistoryControllerTest {
    private fun TestScope.history(api: FakeBrowse) = ReceiptHistoryController(api, this)

    @Test
    fun each_status_maps_to_its_row_and_label() = runTest {
        val api = FakeBrowse().apply {
            page = ApiResult.Success(
                ReceiptPageDto(
                    listOf(
                        summary("a", ReceiptStatusDto.Draft),
                        summary("b", ReceiptStatusDto.Confirmed),
                        summary("c", ReceiptStatusDto.Processing, merchant = null, payer = null, total = 0),
                        summary("d", ReceiptStatusDto.Failed, merchant = null, payer = null),
                        summary("e", ReceiptStatusDto.Void),
                    ),
                ),
            )
        }
        val c = history(api)

        c.load("h1")
        advanceUntilIdle()

        val rows = c.state.value.rows
        assertEquals(
            listOf(
                ReceiptStatus.NeedsReview,
                ReceiptStatus.Confirmed,
                ReceiptStatus.Reading,
                ReceiptStatus.Failed,
                ReceiptStatus.Voided
            ),
            rows.map { it.entry.status },
        )
        assertEquals(listOf("a", "b", "c", "d", "e"), rows.map { it.id })
        assertEquals("Walmart", rows[0].entry.store)
        assertEquals("Reading receipt", rows[2].entry.store)
        assertEquals("Receipt", rows[3].entry.store)
        assertEquals("Sep 18, 2026", rows[0].entry.date)
        assertEquals(877, rows[0].entry.totalCents)
        assertEquals("TJ", rows[0].entry.payer.name)
        assertEquals("no one yet", rows[2].entry.payer.name)
        assertTrue(c.state.value.loaded)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun a_server_that_cannot_be_reached_keeps_the_rows_it_had_and_says_so() = runTest {
        val api = FakeBrowse().apply {
            page = ApiResult.Success(ReceiptPageDto(listOf(summary("a", ReceiptStatusDto.Draft))))
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        api.page = ApiResult.Failure(ApiFailure.Offline)
        c.load("h1")
        advanceUntilIdle()

        assertEquals(1, c.state.value.rows.size)
        assertEquals("Could not reach the server.", c.state.value.message)
        assertFalse(c.state.value.loading)
    }

    @Test
    fun a_failure_to_load_the_household_is_reported_before_any_receipts_are_asked_for() = runTest {
        val api = FakeBrowse().apply {
            people = ApiResult.Failure(ApiFailure.Validation(ApiError("not_found", "household not found")))
        }
        val c = history(api)

        c.load("h1")
        advanceUntilIdle()

        assertEquals("household not found", c.state.value.message)
        assertEquals(0, api.listCalls)
    }

    @Test
    fun a_person_who_left_is_not_offered_but_unknown_payers_do_not_crash_the_list() = runTest {
        val api = FakeBrowse().apply {
            people = ApiResult.Success(listOf(person("p1", "TJ"), person("p9", "Gone", archived = true)))
            page = ApiResult.Success(ReceiptPageDto(listOf(summary("a", ReceiptStatusDto.Confirmed, payer = "p9"))))
        }
        val c = history(api)

        c.load("h1")
        advanceUntilIdle()

        assertEquals("no one yet", c.state.value.rows.single().entry.payer.name)
    }

    private fun threePages() = { query: ReceiptQuery ->
        when (query.cursor) {
            null -> ApiResult.Success(ReceiptPageDto(summaries(1..3), nextCursor = "c1"))
            "c1" -> ApiResult.Success(ReceiptPageDto(summaries(4..6), nextCursor = "c2"))
            else -> ApiResult.Success(ReceiptPageDto(summaries(7..8)))
        }
    }

    @Test
    fun scrolling_loads_every_page_until_the_server_has_no_more() = runTest {
        val api = FakeBrowse().apply { pages = threePages() }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()
        assertTrue(c.state.value.hasMore)

        c.loadMore()
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()

        assertEquals((1..8).map { "r$it" }, c.state.value.rows.map { it.id })
        assertFalse(c.state.value.hasMore)
        c.loadMore()
        advanceUntilIdle()
        assertEquals(3, api.queries.size)
        assertEquals(listOf(null, "c1", "c2"), api.queries.map { it.cursor })
    }

    @Test
    fun a_receipt_the_server_repeats_is_shown_once() = runTest {
        val api = FakeBrowse().apply {
            pages = { query ->
                if (query.cursor == null) ApiResult.Success(ReceiptPageDto(summaries(1..3), nextCursor = "c1"))
                else ApiResult.Success(ReceiptPageDto(summaries(3..5)))
            }
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        c.loadMore()
        advanceUntilIdle()

        assertEquals((1..5).map { "r$it" }, c.state.value.rows.map { it.id })
    }

    @Test
    fun asking_for_more_while_a_page_is_loading_sends_one_request() = runTest {
        val api = FakeBrowse().apply { pages = threePages() }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        c.loadMore()
        c.loadMore()
        advanceUntilIdle()

        assertEquals(2, api.queries.size)
    }

    @Test
    fun a_failed_page_keeps_the_rows_says_why_and_can_be_tried_again() = runTest {
        var failing = true
        val api = FakeBrowse().apply {
            pages = { query ->
                when {
                    query.cursor == null -> ApiResult.Success(ReceiptPageDto(summaries(1..3), nextCursor = "c1"))
                    failing -> ApiResult.Failure(ApiFailure.Offline)
                    else -> ApiResult.Success(ReceiptPageDto(summaries(4..5)))
                }
            }
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        c.loadMore()
        advanceUntilIdle()
        assertEquals(3, c.state.value.rows.size)
        assertEquals("Could not reach the server.", c.state.value.message)
        assertFalse(c.state.value.loadingMore)
        assertTrue(c.state.value.hasMore)

        failing = false
        c.loadMore()
        advanceUntilIdle()
        assertEquals((1..5).map { "r$it" }, c.state.value.rows.map { it.id })
        assertNull(c.state.value.message)
    }

    @Test
    fun searching_by_store_waits_for_a_pause_and_starts_from_the_first_page() = runTest {
        val api = FakeBrowse().apply {
            pages = { query ->
                if (query.merchant != null) ApiResult.Success(ReceiptPageDto(summaries(9..9)))
                else ApiResult.Success(ReceiptPageDto(summaries(1..3), nextCursor = "c1"))
            }
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()

        c.setStore("w")
        c.setStore("wal")
        c.setStore("  walmart ")
        assertEquals("  walmart ", c.state.value.filter.store)
        assertTrue(c.state.value.rows.isEmpty())
        advanceUntilIdle()

        val search = api.queries.filter { it.merchant != null }
        assertEquals(1, search.size)
        assertEquals("walmart", search.single().merchant)
        assertNull(search.single().cursor)
        assertEquals(listOf("r9"), c.state.value.rows.map { it.id })
    }

    @Test
    fun status_and_date_filters_are_sent_and_cleared() = runTest {
        val api = FakeBrowse().apply { pages = threePages() }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        c.setStatus(ReceiptStatusDto.Draft)
        advanceUntilIdle()
        assertEquals(ReceiptStatusDto.Draft, api.queries.last().status)

        c.setDates("2026-09-01", "2026-09-30")
        advanceUntilIdle()
        assertEquals("2026-09-01", api.queries.last().from)
        assertEquals("2026-09-30", api.queries.last().to)
        assertEquals(ReceiptStatusDto.Draft, api.queries.last().status)
        assertTrue(c.state.value.filter.active)

        c.clearFilters()
        advanceUntilIdle()
        val last = api.queries.last()
        assertNull(last.status)
        assertNull(last.from)
        assertNull(last.to)
        assertNull(last.merchant)
        assertFalse(c.state.value.filter.active)
    }

    @Test
    fun coming_back_refreshes_the_first_page_and_keeps_the_pages_already_loaded() = runTest {
        val api = FakeBrowse().apply {
            pages = { query ->
                if (query.cursor == null) ApiResult.Success(ReceiptPageDto(summaries(1..3), nextCursor = "c1"))
                else ApiResult.Success(ReceiptPageDto(summaries(4..6)))
            }
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()
        c.loadMore()
        advanceUntilIdle()
        assertEquals((1..6).map { "r$it" }, c.state.value.rows.map { it.id })

        api.pages = { query ->
            if (query.cursor == null) ApiResult.Success(ReceiptPageDto(summaries(0..3), nextCursor = "c1"))
            else ApiResult.Success(ReceiptPageDto(summaries(4..6)))
        }
        c.load("h1")
        advanceUntilIdle()

        assertEquals((0..6).map { "r$it" }, c.state.value.rows.map { it.id })
        assertFalse(c.state.value.hasMore)
        assertFalse(c.state.value.loading)
        assertNull(c.state.value.message)
    }

    @Test
    fun a_refreshed_receipt_replaces_its_old_row() = runTest {
        val api = FakeBrowse().apply {
            page = ApiResult.Success(ReceiptPageDto(listOf(summary("a", ReceiptStatusDto.Draft))))
        }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()

        api.page = ApiResult.Success(ReceiptPageDto(listOf(summary("a", ReceiptStatusDto.Confirmed))))
        c.load("h1")
        advanceUntilIdle()

        assertEquals(listOf(ReceiptStatus.Confirmed), c.state.value.rows.map { it.entry.status })
    }

    @Test
    fun another_household_starts_with_an_empty_list() = runTest {
        val api = FakeBrowse().apply { pages = threePages() }
        val c = history(api)
        c.load("h1")
        advanceUntilIdle()
        c.setStatus(ReceiptStatusDto.Draft)
        advanceUntilIdle()

        c.load("h2")

        assertTrue(c.state.value.rows.isEmpty())
        assertFalse(c.state.value.filter.active)
    }
}

class ReceiptDetailControllerTest {
    private fun TestScope.detail(api: FakeBrowse) = ReceiptDetailController(api, this)

    private val confirmed = ReceiptDto(
        id = "r1",
        householdId = "h1",
        status = ReceiptStatusDto.Confirmed,
        paidByPersonId = "p2",
        merchantName = "Walmart",
        purchasedAt = "2026-09-18T12:00:00Z",
        tax = MoneyDto(41),
        total = MoneyDto(877),
        hasImage = true,
        items = listOf(
            item("a", 500, "p1" to 1),
            item("b", 300, "p1" to 1, "p2" to 1),
            item("c", 36),
        ),
    )

    @Test
    fun a_confirmed_receipt_shows_its_lines_payer_and_each_persons_share() = runTest {
        val api = FakeBrowse().apply { detail = ApiResult.Success(confirmed) }
        val c = detail(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        val state = c.state.value
        assertEquals(DetailPhase.Ready, state.phase)
        assertEquals("Walmart", state.receipt?.store)
        assertEquals("Sumedh", state.receipt?.payer?.name)
        assertEquals(listOf("TJ" to 650, "Sumedh" to 150), state.shares.map { (p, cents) -> p.name to cents })
        assertTrue(state.hasPhoto)
    }

    @Test
    fun a_missing_receipt_or_household_is_an_error_with_a_message() = runTest {
        val api = FakeBrowse().apply {
            detail = ApiResult.Failure(ApiFailure.Validation(ApiError("not_found", "receipt not found")))
        }
        val c = detail(api)

        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(DetailPhase.Error, c.state.value.phase)
        assertEquals("receipt not found", c.state.value.message)
    }

    @Test
    fun reopening_hands_the_receipt_to_the_review_screen() = runTest {
        val api = FakeBrowse().apply { detail = ApiResult.Success(confirmed) }
        val c = detail(api)
        c.open("h1", "r1")
        advanceUntilIdle()
        var reopened: String? = null

        c.reopen { reopened = it }
        advanceUntilIdle()

        assertEquals("r1", reopened)
    }

    @Test
    fun a_refused_reopen_stays_on_the_detail_with_a_message() = runTest {
        val api = FakeBrowse().apply {
            detail = ApiResult.Success(confirmed)
            reopened = ApiResult.Failure(
                ApiFailure.Validation(
                    ApiError(
                        "not_confirmed",
                        "only a confirmed receipt can be reopened"
                    )
                )
            )
        }
        val c = detail(api)
        c.open("h1", "r1")
        advanceUntilIdle()
        var called = false

        c.reopen { called = true }
        advanceUntilIdle()

        assertFalse(called)
        assertEquals(DetailPhase.Ready, c.state.value.phase)
        assertTrue(c.state.value.message!!.contains("only a confirmed receipt can be reopened"))
    }

    @Test
    fun the_photo_is_fetched_on_demand_and_null_when_it_cannot_be_loaded() = runTest {
        val api = FakeBrowse().apply { detail = ApiResult.Success(confirmed) }
        val c = detail(api)
        c.open("h1", "r1")
        advanceUntilIdle()

        assertEquals(listOf<Byte>(1, 2, 3), c.photo()?.toList())

        api.photo = ApiResult.Failure(ApiFailure.Offline)
        assertNull(c.photo())
    }

    @Test
    fun closing_clears_the_screen() = runTest {
        val api = FakeBrowse().apply { detail = ApiResult.Success(confirmed) }
        val c = detail(api)
        c.open("h1", "r1")
        advanceUntilIdle()

        c.close()

        assertEquals(DetailPhase.Idle, c.state.value.phase)
        assertNull(c.state.value.receipt)
    }
}
