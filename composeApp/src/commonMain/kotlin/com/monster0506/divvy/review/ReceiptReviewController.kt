package com.monster0506.divvy.review

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReviewPhase {
    Idle,

    /** The photo is being read on the server. */
    Reading,

    /** Reading failed; [ReviewState.message] says why and [ReceiptReviewController.retry] tries again. */
    Failed,

    /** The server could not be reached, or the receipt could not be loaded. */
    Unreachable,
    Ready,
    Confirming,
    Confirmed,
}

data class ReviewState(
    val phase: ReviewPhase = ReviewPhase.Idle,
    /** Why reading failed, or the last problem to show; cleared by the next successful action. */
    val message: String? = null,
    val draft: ReceiptDraft? = null,
    val receiptId: String? = null,
)

/**
 * Drives one receipt from upload to confirmation: it waits while the server reads the photo, loads the draft with
 * the model's guesses, saves each change as it is made, and confirms. Changes are shown at once and put back if
 * the server refuses them.
 */
class ReceiptReviewController(
    private val api: ReviewApi,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1_500,
    private val readTimeoutMillis: Long = 180_000,
    private val maxMissedPolls: Int = 5,
) {
    private val mutableState = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = mutableState

    private var householdId = ""
    private var receiptId = ""
    private var preferredPayerId: String? = null
    private var people: List<Person> = emptyList()
    private var itemIds: Map<Int, String> = emptyMap()
    private var payerSaved = false
    private var job: Job? = null
    private val writes = Mutex()

    /** Starts following [receiptId]. [payerId] is who paid when the photo does not say; it defaults to the first member. */
    fun open(householdId: String, receiptId: String, payerId: String? = null) {
        this.householdId = householdId
        this.receiptId = receiptId
        this.preferredPayerId = payerId
        job?.cancel()
        mutableState.value = ReviewState(ReviewPhase.Reading, receiptId = receiptId)
        job = scope.launch { guarded { follow() } }
    }

    fun close() {
        job?.cancel()
        mutableState.value = ReviewState()
    }

    /** Reads a failed photo again, or looks again after the server could not be reached. */
    fun retry() {
        val phase = mutableState.value.phase
        if (phase != ReviewPhase.Failed && phase != ReviewPhase.Unreachable) return
        job?.cancel()
        mutableState.update { it.copy(phase = ReviewPhase.Reading, message = null) }
        job = scope.launch {
            if (phase == ReviewPhase.Failed) {
                val result = api.retry(householdId, receiptId)
                if (result is ApiResult.Failure) return@launch fail(ReviewPhase.Unreachable, describe(result.failure))
            }
            guarded { follow() }
        }
    }

    fun toggleOwner(lineId: Int, personId: String) {
        val line = mutableState.value.draft?.lines?.find { it.id == lineId } ?: return
        setOwners(lineId, if (personId in line.owners) line.owners - personId else line.owners + personId)
    }

    fun setOwners(lineId: Int, owners: Set<String>) {
        val itemId = itemIds[lineId] ?: return
        val before = mutableState.value.draft?.lines?.find { it.id == lineId } ?: return
        updateLine(lineId) { it.copy(owners = owners, confirmed = owners.isNotEmpty(), confidence = if (owners.isEmpty()) 0f else 1f) }
        scope.launch {
            val result = writes.withLock { api.setOwners(receiptId, itemId, owners.map { ItemOwnerDto(it) }) }
            if (result is ApiResult.Failure) {
                updateLine(lineId) { before }
                mutableState.update { it.copy(message = "Could not save that change. ${describe(result.failure)}") }
            }
        }
    }

    /** Keeps the model's guess for a line as it is. */
    fun accept(lineId: Int) {
        val itemId = itemIds[lineId] ?: return
        updateLine(lineId) { it.copy(confirmed = true) }
        scope.launch {
            val result = writes.withLock { api.accept(householdId, receiptId, listOf(itemId)) }
            if (result is ApiResult.Failure) {
                updateLine(lineId) { it.copy(confirmed = false) }
                mutableState.update { it.copy(message = "Could not confirm that item. ${describe(result.failure)}") }
            }
        }
    }

    fun confirm() {
        val current = mutableState.value
        if (current.phase != ReviewPhase.Ready) return
        if (!payerSaved) {
            mutableState.update { it.copy(message = "Choose who paid before confirming.") }
            return
        }
        mutableState.update { it.copy(phase = ReviewPhase.Confirming, message = null) }
        scope.launch {
            when (val result = writes.withLock { api.confirm(receiptId) }) {
                is ApiResult.Success -> mutableState.update { it.copy(phase = ReviewPhase.Confirmed) }
                is ApiResult.Failure -> {
                    val failure = result.failure
                    val pending = (failure as? ApiFailure.Validation)?.error?.takeIf { it.code == "unconfirmed_items" }?.details.orEmpty()
                    mutableState.update { it.copy(phase = ReviewPhase.Ready, message = describe(failure)) }
                    pending.forEach { itemId ->
                        itemIds.entries.find { it.value == itemId }?.key?.let { id -> updateLine(id) { line -> line.copy(confirmed = false) } }
                    }
                }
            }
        }
    }

    private suspend fun follow() {
        val loaded = when (val result = api.people(householdId)) {
            is ApiResult.Success -> result.value.filter { it.archivedAt == null }
            is ApiResult.Failure -> return fail(ReviewPhase.Unreachable, describe(result.failure))
        }
        if (loaded.isEmpty()) return fail(ReviewPhase.Unreachable, "This household has no members yet.")
        people = loaded.mapIndexed { index, person -> person.toUiPerson(index) }

        var missed = 0
        val polls = (readTimeoutMillis / pollMillis).coerceAtLeast(1)
        repeat(polls.toInt()) {
            when (val result = api.receipt(receiptId)) {
                is ApiResult.Success -> {
                    missed = 0
                    val receipt = result.value
                    when (receipt.status) {
                        ReceiptStatusDto.Processing -> Unit
                        ReceiptStatusDto.Failed -> return fail(ReviewPhase.Failed, receipt.failureReason ?: "The photo could not be read.")
                        ReceiptStatusDto.Draft -> return showDraft(receipt, loaded)
                        ReceiptStatusDto.Confirmed ->
                            return mutableState.update { it.copy(phase = ReviewPhase.Confirmed, message = null) }
                        ReceiptStatusDto.Void -> return fail(ReviewPhase.Failed, "This receipt was voided.")
                    }
                }
                is ApiResult.Failure -> {
                    val failure = result.failure
                    missed++
                    if (failure is ApiFailure.Unauthorised || (failure is ApiFailure.Validation && failure.error.code == "not_found")) {
                        return fail(ReviewPhase.Unreachable, describe(failure))
                    }
                    if (missed >= maxMissedPolls) return fail(ReviewPhase.Unreachable, describe(failure))
                }
            }
            delay(pollMillis)
        }
        fail(ReviewPhase.Failed, "Reading this receipt is taking longer than expected. Try again in a moment.")
    }

    private suspend fun showDraft(receipt: ReceiptDto, members: List<PersonDto>) {
        val predictions = (api.predictions(householdId, receiptId) as? ApiResult.Success)?.value
        val payerId = receipt.paidByPersonId
            ?: preferredPayerId?.takeIf { id -> members.any { it.id == id } }
            ?: members.first().id
        var message: String? = null
        payerSaved = receipt.paidByPersonId != null
        if (!payerSaved) {
            when (val saved = api.setPayer(receiptId, payerId)) {
                is ApiResult.Success -> payerSaved = true
                is ApiResult.Failure -> message = "Could not save who paid. ${describe(saved.failure)}"
            }
        }
        val payer = people.find { it.id == payerId } ?: people.first()
        val lines = buildReviewLines(receipt, people, payer, predictions)
        itemIds = lines.itemIds
        mutableState.value = ReviewState(ReviewPhase.Ready, message, lines.draft, receiptId)
    }

    /** A bug or an unreadable response must end in a message the person can see, never a screen that waits forever. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ReviewPhase.Unreachable, "Something went wrong loading this receipt. ${e.message.orEmpty()}".trim())
        }
    }

    private fun fail(phase: ReviewPhase, message: String) {
        mutableState.update { it.copy(phase = phase, message = message) }
    }

    private fun updateLine(lineId: Int, change: (com.monster0506.divvy.ui.model.ReceiptLine) -> com.monster0506.divvy.ui.model.ReceiptLine) {
        mutableState.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = draft.copy(lines = draft.lines.map { if (it.id == lineId) change(it) else it }))
        }
    }

    private fun describe(failure: ApiFailure): String = when (failure) {
        ApiFailure.Offline -> "Could not reach the server."
        ApiFailure.Unauthorised -> "The server rejected the API key."
        is ApiFailure.Validation -> failure.error.message
        is ApiFailure.ServerError -> "The server had a problem (${failure.status})."
    }
}
