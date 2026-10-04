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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the list: the receipt's id, its server status and what the list shows for it. */
data class HistoryRow(val id: String, val status: ReceiptStatusDto, val entry: HistoryEntry)

data class HistoryState(
    val rows: List<HistoryRow> = emptyList(),
    val loading: Boolean = false,
    /** Why the list could not be loaded; the rows already shown are kept. */
    val message: String? = null,
    val loaded: Boolean = false,
)

/** Loads the household's receipts, newest first, for the Receipts tab. */
class ReceiptHistoryController(private val api: BrowseApi, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutableState
    private var job: Job? = null

    fun load(householdId: String) {
        job?.cancel()
        mutableState.update { it.copy(loading = true, message = null) }
        job = scope.launch {
            val people = when (val result = api.people(householdId)) {
                is ApiResult.Success -> result.value.filter { it.archivedAt == null }.mapIndexed { i, p -> p.toUiPerson(i) }
                is ApiResult.Failure -> return@launch fail(result.failure)
            }
            when (val page = api.receipts(householdId)) {
                is ApiResult.Success ->
                    mutableState.value = HistoryState(page.value.items.map { it.toRow(people) }, loading = false, loaded = true)
                is ApiResult.Failure -> fail(page.failure)
            }
        }
    }

    private fun fail(failure: ApiFailure) {
        mutableState.update { it.copy(loading = false, message = describeFailure(failure)) }
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
