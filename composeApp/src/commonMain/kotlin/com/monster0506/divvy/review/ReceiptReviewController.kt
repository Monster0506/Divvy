package com.monster0506.divvy.review

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.OFFLINE_MESSAGE
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.EditedReceiptDto
import com.monster0506.divvy.data.dto.ItemEditRequest
import com.monster0506.divvy.data.dto.ItemOwnerDto
import com.monster0506.divvy.data.dto.UpdateReceiptRequest
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.data.dto.ReceiptDto
import com.monster0506.divvy.data.dto.ReceiptStatusDto
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The receipt's own fields as a person typed them. [dateIso] is `YYYY-MM-DD`. */
data class ReceiptDetailsEdit(val store: String, val dateIso: String, val taxCents: Int, val totalCents: Int)

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
    /** Set when the lines do not add up to the receipt's printed amounts. */
    val warning: String? = null,
    /** True when the last deleted line can be put back. */
    val canUndo: Boolean = false,
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
    private val onPayerChosen: (personId: String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = mutableState

    private var householdId = ""
    private var receiptId = ""
    private var preferredPayerId: String? = null
    private var people: List<Person> = emptyList()
    private var itemIds: Map<Int, String> = emptyMap()
    private var savedOwners: Map<String, Set<String>> = emptyMap()
    private var savedConfirmed: Map<String, Boolean> = emptyMap()
    private var savedConfidence: Map<String, Float> = emptyMap()
    private val confirmingLines = mutableSetOf<String>()
    private var payerSaved = false
    private var lastDeleted: ItemEditRequest? = null
    private var job: Job? = null
    private val writes = Mutex()

    /** Starts following [receiptId]. [payerId] is who paid last time; it is used only if they are still a member. */
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
        confirmingLines.clear()
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

    /** Records who paid. The change shows at once and is put back if the server refuses it. */
    fun setPayer(personId: String) {
        val current = mutableState.value
        val draft = current.draft ?: return
        val person = people.find { it.id == personId } ?: return
        if (current.phase != ReviewPhase.Ready || (draft.payerChosen && draft.payer.id == personId)) return
        val wasSaved = payerSaved
        mutableState.update { it.copy(draft = draft.copy(payer = person, payerChosen = true), message = null) }
        scope.launch {
            when (val result = writes.withLock { api.setPayer(receiptId, personId) }) {
                is ApiResult.Success -> {
                    payerSaved = true
                    onPayerChosen(personId)
                }
                is ApiResult.Failure -> {
                    payerSaved = wasSaved
                    mutableState.update { state ->
                        val back = state.draft?.copy(payer = draft.payer, payerChosen = draft.payerChosen)
                        state.copy(draft = back, message = "Could not save who paid. ${describe(result.failure)}")
                    }
                }
            }
        }
    }

    fun toggleOwner(lineId: Int, personId: String) {
        val line = mutableState.value.draft?.lines?.find { it.id == lineId } ?: return
        setOwners(lineId, if (personId in line.owners) line.owners - personId else line.owners + personId)
    }

    /** Keeps owner choices visible until the person confirms the whole selection. */
    fun setOwners(lineId: Int, owners: Set<String>) {
        val itemId = itemIds[lineId] ?: return
        if (itemId in confirmingLines || mutableState.value.phase != ReviewPhase.Ready) return
        updateLine(lineId) {
            it.copy(
                owners = owners,
                confirmed = owners.isNotEmpty() && owners == savedOwners[itemId] && savedConfirmed[itemId] == true,
                confidence = if (owners.isEmpty()) 0f else if (owners == savedOwners[itemId]) savedConfidence[itemId] ?: it.confidence else 1f,
            )
        }
    }

    /** Locks in the selected owners, or accepts an unchanged model guess. */
    fun accept(lineId: Int) {
        val itemId = itemIds[lineId] ?: return
        val line = mutableState.value.draft?.lines?.find { it.id == lineId } ?: return
        if (line.owners.isEmpty() || line.confirmed || mutableState.value.phase != ReviewPhase.Ready || !confirmingLines.add(itemId)) return
        val owners = line.owners
        scope.launch {
            try {
                val result = writes.withLock {
                    if (owners == savedOwners[itemId]) api.accept(householdId, receiptId, listOf(itemId))
                    else api.setOwners(receiptId, itemId, owners.map { ItemOwnerDto(it) })
                }
                when (result) {
                    is ApiResult.Success -> {
                        savedOwners = savedOwners + (itemId to owners)
                        savedConfirmed = savedConfirmed + (itemId to true)
                        savedConfidence = savedConfidence + (itemId to 1f)
                        updateLine(lineId) { if (it.owners == owners) it.copy(confirmed = true, confidence = 1f) else it }
                        mutableState.update { it.copy(message = null) }
                    }
                    is ApiResult.Failure -> mutableState.update {
                        it.copy(message = "Could not confirm that item. ${describe(result.failure)}")
                    }
                }
            } finally {
                confirmingLines.remove(itemId)
            }
        }
    }

    /** Saves a changed store, date, tax or total. Only what differs from what is shown is sent. */
    fun editDetails(details: ReceiptDetailsEdit) {
        val draft = mutableState.value.draft ?: return
        val store = details.store.trim().takeIf { it != draft.store }
        val date = details.dateIso.takeIf { it != draft.dateIso }
        val tax = details.taxCents.takeIf { it != draft.taxCents }
        val total = details.totalCents.takeIf { it != draft.printedTotalCents }
        if (store == null && date == null && tax == null && total == null) return
        val change = UpdateReceiptRequest(
            merchantName = store,
            purchasedAt = date?.let { "${it}T12:00:00Z" },
            taxMinor = tax?.toLong(),
            totalMinor = total?.toLong(),
        )
        edit { api.updateDetails(receiptId, change) }
    }

    /** Renames a line and changes its price. */
    fun editLine(lineId: Int, name: String, priceCents: Int) {
        val itemId = itemIds[lineId] ?: return
        edit { api.updateItem(receiptId, itemId, ItemEditRequest(description = name.trim(), totalMinor = priceCents.toLong())) }
    }

    fun addLine(name: String, priceCents: Int) {
        edit { api.addItem(receiptId, ItemEditRequest(description = name.trim(), totalMinor = priceCents.toLong())) }
    }

    /** Removes a line. [undoDelete] puts it back (without its owners). */
    fun deleteLine(lineId: Int) {
        val itemId = itemIds[lineId] ?: return
        val line = mutableState.value.draft?.lines?.find { it.id == lineId } ?: return
        val removed = ItemEditRequest(description = line.name, totalMinor = line.priceCents.toLong())
        edit(onSuccess = { lastDeleted = removed; mutableState.update { it.copy(canUndo = true) } }) {
            api.deleteItem(receiptId, itemId)
        }
    }

    fun undoDelete() {
        val removed = lastDeleted ?: return
        edit(onSuccess = { lastDeleted = null; mutableState.update { it.copy(canUndo = false) } }) {
            api.addItem(receiptId, removed)
        }
    }

    /** Combines lines into the first of them, adding their prices. */
    fun mergeLines(lineIds: Set<Int>) {
        val ids = lineIds.sorted().mapNotNull { itemIds[it] }
        if (ids.size < 2) return
        edit { api.mergeItems(receiptId, ids) }
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

    /** Sends a change to the server and shows the receipt it answers with, or says why it could not be saved. */
    private fun edit(onSuccess: () -> Unit = {}, call: suspend () -> ApiResult<EditedReceiptDto>) {
        if (mutableState.value.phase != ReviewPhase.Ready) return
        scope.launch {
            when (val result = writes.withLock { call() }) {
                is ApiResult.Success -> {
                    onSuccess()
                    applyEdited(result.value)
                }
                is ApiResult.Failure ->
                    mutableState.update { it.copy(message = "Could not save that change. ${describe(result.failure)}") }
            }
        }
    }

    /** Rebuilds the lines from an edit response, keeping what is known about the lines that were already there. */
    private fun applyEdited(edited: EditedReceiptDto) {
        mutableState.update { state ->
            val draft = state.draft ?: return@update state
            val previousOwners = savedOwners
            val previousConfirmed = savedConfirmed
            val previousConfidence = savedConfidence
            val before = itemIds.entries.associate { (lineId, itemId) -> itemId to draft.lines.find { it.id == lineId } }
            val ordered = edited.items.sortedBy { it.lineNumber }
            val ids = ordered.mapIndexed { index, item -> (if (item.lineNumber > 0) item.lineNumber else index + 1) to item.id }.toMap()
            val lines = ordered.mapIndexed { index, item ->
                val old = before[item.id]
                val persistedOwners = item.owners.map { it.personId }.toSet()
                val chosenOwners = if (old != null && old.owners != previousOwners[item.id]) old.owners else persistedOwners
                ReceiptLine(
                    id = if (item.lineNumber > 0) item.lineNumber else index + 1,
                    name = item.description,
                    priceCents = item.totalMinor.toInt(),
                    owners = chosenOwners,
                    confidence = old?.confidence ?: 0f,
                    confirmed = chosenOwners.isNotEmpty() && chosenOwners == persistedOwners &&
                        previousOwners[item.id] == persistedOwners && previousConfirmed[item.id] == true,
                )
            }
            itemIds = ids
            savedOwners = ordered.associate { it.id to it.owners.map { owner -> owner.personId }.toSet() }
            savedConfirmed = ordered.associate { item ->
                item.id to (savedOwners[item.id]?.isNotEmpty() == true &&
                    previousOwners[item.id] == savedOwners[item.id] && previousConfirmed[item.id] == true)
            }
            savedConfidence = ordered.associate { item -> item.id to (previousConfidence[item.id] ?: 0f) }
            state.copy(
                draft = draft.copy(
                    lines = lines,
                    store = edited.merchantName ?: draft.store,
                    date = edited.purchasedAt?.let(::formatDate) ?: draft.date,
                    dateIso = edited.purchasedAt?.take(DATE_LENGTH) ?: draft.dateIso,
                    taxCents = edited.taxMinor?.toInt() ?: draft.taxCents,
                    printedTotalCents = edited.totalMinor.toInt(),
                ),
                warning = reconciliationWarning(edited.reconciliation),
                message = null,
            )
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
        val payerId = receipt.paidByPersonId ?: preferredPayerId?.takeIf { id -> members.any { it.id == id } }
        var message: String? = null
        payerSaved = receipt.paidByPersonId != null
        if (payerId != null && !payerSaved) {
            when (val saved = api.setPayer(receiptId, payerId)) {
                is ApiResult.Success -> payerSaved = true
                is ApiResult.Failure -> message = "Could not save who paid. ${describe(saved.failure)}"
            }
        }
        val payer = people.find { it.id == payerId } ?: people.first()
        val lines = buildReviewLines(receipt, people, payer, predictions).let {
            it.copy(draft = it.draft.copy(payerChosen = payerId != null))
        }
        itemIds = lines.itemIds
        savedOwners = receipt.items.associate { it.id to it.owners.map { owner -> owner.personId }.toSet() }
        savedConfirmed = lines.draft.lines.associate { line -> lines.itemIds.getValue(line.id) to line.confirmed }
        savedConfidence = lines.draft.lines.associate { line -> lines.itemIds.getValue(line.id) to line.confidence }
        confirmingLines.clear()
        lastDeleted = null
        mutableState.value = ReviewState(
            ReviewPhase.Ready, message, lines.draft, receiptId, reconciliationWarning(reconcile(receipt)),
        )
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
        ApiFailure.Offline -> OFFLINE_MESSAGE
        ApiFailure.Unauthorised -> "The server rejected the API key."
        is ApiFailure.Validation -> failure.error.message
        is ApiFailure.ServerError -> "The server had a problem (${failure.status})."
    }
}
