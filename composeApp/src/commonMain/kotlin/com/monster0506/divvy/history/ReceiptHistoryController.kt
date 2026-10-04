package com.monster0506.divvy.history

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.data.dto.ReceiptSummaryDto
import com.monster0506.divvy.review.formatDate
import com.monster0506.divvy.review.toUiPerson
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.HistoryEntry
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the list: the receipt's id, its server status and what the list shows for it. */
data class HistoryRow(val id: String, val status: ReceiptStatusDto, val entry: HistoryEntry)

/** What narrows the list. [fromIso] and [toIso] are `YYYY-MM-DD`; blank or null means no limit. */
data class ReceiptFilter(
    val store: String = "",
    val status: ReceiptStatusDto? = null,
    val fromIso: String? = null,
    val toIso: String? = null,
) {
    val active: Boolean get() = store.isNotBlank() || status != null || fromIso != null || toIso != null
}

data class HistoryState(
    val rows: List<HistoryRow> = emptyList(),
    val loading: Boolean = false,
    /** Why the list could not be loaded; the rows already shown are kept. */
    val message: String? = null,
    val loaded: Boolean = false,
    val filter: ReceiptFilter = ReceiptFilter(),
    /** True while the next page is being fetched. */
    val loadingMore: Boolean = false,
    /** True when the server has more receipts after the last row. */
    val hasMore: Boolean = false,
)

/**
 * Loads the household's receipts, newest first, for the Receipts tab: a page at a time as the person scrolls,
 * narrowed by a store search and status and date filters. Coming back to a list that is already loaded refreshes
 * it in place, so the rows (and the scroll position over them) stay.
 */
class ReceiptHistoryController(
    private val api: BrowseApi,
    private val scope: CoroutineScope,
    private val pageSize: Int = 50,
    private val searchDelayMillis: Long = 350,
) {
    private val mutableState = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutableState

    private var householdId = ""
    private var people: List<Person> = emptyList()
    private var nextCursor: String? = null
    private var pagesLoaded = 0
    private var job: Job? = null
    private var searchJob: Job? = null

    fun load(householdId: String) {
        if (householdId != this.householdId) {
            job?.cancel()
            searchJob?.cancel()
            this.householdId = householdId
            people = emptyList()
            nextCursor = null
            pagesLoaded = 0
            mutableState.value = HistoryState()
        }
        fetchFirstPage(keepRows = mutableState.value.loaded && mutableState.value.filter.status == null)
    }

    /** Fetches the next page when there is one and nothing else is loading. */
    fun loadMore() {
        val cursor = nextCursor ?: return
        val current = mutableState.value
        if (householdId.isEmpty() || !current.loaded || current.loading || current.loadingMore) return
        val filter = current.filter
        mutableState.update { it.copy(loadingMore = true, message = null) }
        job = scope.launch {
            when (val page = api.receipts(householdId, query(filter, cursor))) {
                is ApiResult.Success -> {
                    nextCursor = page.value.nextCursor
                    pagesLoaded++
                    val more = page.value.items.map { it.toRow(people) }
                    mutableState.update { state ->
                        val known = state.rows.mapTo(HashSet()) { it.id }
                        state.copy(
                            rows = state.rows + more.filter { it.id !in known },
                            loadingMore = false,
                            hasMore = nextCursor != null,
                        )
                    }
                }
                is ApiResult.Failure -> fail(page.failure)
            }
        }
    }

    /** Searches by store name once typing pauses. */
    fun setStore(text: String) = changeFilter(searchDelayMillis) { it.copy(store = text) }

    fun setStatus(status: ReceiptStatusDto?) = changeFilter(0) { it.copy(status = status) }

    /** [fromIso] and [toIso] are `YYYY-MM-DD`, or null for no limit. */
    fun setDates(fromIso: String?, toIso: String?) = changeFilter(0) { it.copy(fromIso = fromIso, toIso = toIso) }

    fun clearFilters() = changeFilter(0) { ReceiptFilter() }

    private fun changeFilter(delayMillis: Long, change: (ReceiptFilter) -> ReceiptFilter) {
        val filter = change(mutableState.value.filter)
        job?.cancel()
        searchJob?.cancel()
        // The list shown belongs to the old filter, so it goes at once and the new one starts from the first page.
        nextCursor = null
        pagesLoaded = 0
        mutableState.update {
            it.copy(filter = filter, rows = emptyList(), loaded = false, hasMore = false, loadingMore = false, message = null)
        }
        if (householdId.isEmpty()) return
        searchJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            fetchFirstPage(keepRows = false)
        }
    }

    private fun fetchFirstPage(keepRows: Boolean) {
        job?.cancel()
        val filter = mutableState.value.filter
        mutableState.update { it.copy(loading = true, loadingMore = false, message = null) }
        job = scope.launch {
            val members = when (val result = api.people(householdId)) {
                is ApiResult.Success -> result.value.filter { it.archivedAt == null }.mapIndexed { i, p -> p.toUiPerson(i) }
                is ApiResult.Failure -> return@launch fail(result.failure)
            }
            people = members
            when (val page = api.receipts(householdId, query(filter, null))) {
                is ApiResult.Success -> showFirstPage(page.value.items.map { it.toRow(people) }, page.value.nextCursor, keepRows)
                is ApiResult.Failure -> fail(page.failure)
            }
        }
    }

    private fun showFirstPage(fresh: List<HistoryRow>, cursor: String?, keepRows: Boolean) {
        // Later pages stay, so their cursor stays too; with only one page the fresh cursor is the right one.
        if (!keepRows || pagesLoaded <= 1) {
            nextCursor = cursor
            pagesLoaded = 1
        }
        mutableState.update { state ->
            val freshIds = fresh.mapTo(HashSet()) { it.id }
            val rows = if (keepRows) fresh + state.rows.filter { it.id !in freshIds } else fresh
            state.copy(rows = rows, loading = false, loaded = true, hasMore = nextCursor != null)
        }
    }

    private fun query(filter: ReceiptFilter, cursor: String?) = ReceiptQuery(
        cursor = cursor,
        status = filter.status,
        merchant = filter.store.trim().ifBlank { null },
        from = filter.fromIso,
        to = filter.toIso,
        limit = pageSize,
    )

    private fun fail(failure: ApiFailure) {
        mutableState.update { it.copy(loading = false, loadingMore = false, message = describeFailure(failure)) }
    }
}

private val nobody = Person("", "no one yet", "?", AvatarColor.Gray)

internal fun ReceiptSummaryDto.toRow(people: List<Person>): HistoryRow {
    val status = when (status) {
        ReceiptStatusDto.Processing -> ReceiptStatus.Reading
        ReceiptStatusDto.Draft -> ReceiptStatus.NeedsReview
        ReceiptStatusDto.Confirmed -> ReceiptStatus.Confirmed
        ReceiptStatusDto.Failed -> ReceiptStatus.Failed
        ReceiptStatusDto.Void -> ReceiptStatus.Voided
    }
    val store = merchantName?.takeIf { it.isNotBlank() }
        ?: if (this.status == ReceiptStatusDto.Processing) "Reading receipt" else "Receipt"
    return HistoryRow(
        id = id,
        status = this.status,
        entry = HistoryEntry(
            store = store,
            date = formatDate(purchasedAt),
            totalCents = totalMinor.toInt(),
            payer = people.find { it.id == paidByPersonId } ?: nobody,
            status = status,
        ),
    )
}

internal fun describeFailure(failure: ApiFailure): String = when (failure) {
    ApiFailure.Offline -> "Could not reach the server."
    ApiFailure.Unauthorised -> "The server rejected the API key."
    is ApiFailure.Validation -> failure.error.message
    is ApiFailure.ServerError -> "The server had a problem (${failure.status})."
}