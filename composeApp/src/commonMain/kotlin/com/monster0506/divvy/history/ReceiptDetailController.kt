package com.monster0506.divvy.history

import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.domain.receiptShares
import com.monster0506.divvy.review.buildReviewLines
import com.monster0506.divvy.review.toUiPerson
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DetailPhase { Idle, Loading, Ready, Error }

data class DetailState(
    val phase: DetailPhase = DetailPhase.Idle,
    val receiptId: String? = null,
    val receipt: ReceiptDraft? = null,
    /** What each person who owns something on the receipt owes, in cents. */
    val shares: List<Pair<Person, Int>> = emptyList(),
    val hasPhoto: Boolean = false,
    val message: String? = null,
)

/** Loads one confirmed receipt with each person's share, and can reopen it for correction. */
class ReceiptDetailController(private val api: BrowseApi, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = mutableState
    private var job: Job? = null
    private var householdId = ""

    fun open(householdId: String, receiptId: String) {
        this.householdId = householdId
        job?.cancel()
        mutableState.value = DetailState(DetailPhase.Loading, receiptId)
        job = scope.launch {
            val members = when (val result = api.people(householdId)) {
                is ApiResult.Success -> result.value.filter { it.archivedAt == null }
                is ApiResult.Failure -> return@launch error(describeFailure(result.failure))
            }
            val receipt = when (val result = api.receipt(receiptId)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return@launch error(describeFailure(result.failure))
            }
            if (members.isEmpty()) return@launch error("This household has no members yet.")
            val people = members.mapIndexed { index, person -> person.toUiPerson(index) }
            val payer = people.find { it.id == receipt.paidByPersonId } ?: people.first()
            val draft = buildReviewLines(receipt, people, payer, null).draft
            val owed = receiptShares(receipt.items)
            mutableState.value = DetailState(
                phase = DetailPhase.Ready,
                receiptId = receiptId,
                receipt = draft,
                shares = people.mapNotNull { person -> owed[person.id]?.takeIf { it > 0 }?.let { person to it.toInt() } },
                hasPhoto = receipt.hasImage,
            )
        }
    }

    /** Moves the receipt back to a draft; [onReopened] is called with its id so the review screen can take over. */
    fun reopen(onReopened: (String) -> Unit) {
        val id = mutableState.value.receiptId ?: return
        scope.launch {
            when (val result = api.reopen(id)) {
                is ApiResult.Success -> if (result.value.status == ReceiptStatusDto.Draft) onReopened(id)
                is ApiResult.Failure ->
                    mutableState.update { it.copy(message = "Could not reopen this receipt. ${describeFailure(result.failure)}") }
            }
        }
    }

    /** The receipt's photo as sent, or null if it cannot be loaded. */
    suspend fun photo(): ByteArray? {
        val id = mutableState.value.receiptId ?: return null
        return (api.image(id) as? ApiResult.Success)?.value
    }

    fun close() {
        job?.cancel()
        mutableState.value = DetailState()
    }

    private fun error(message: String) {
        mutableState.update { it.copy(phase = DetailPhase.Error, message = message) }
    }
}
