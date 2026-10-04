package com.monster0506.divvy.balances

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.CreateSettlementRequest
import com.monster0506.divvy.data.dto.SettlementDto
import com.monster0506.divvy.data.dto.DEFAULT_CURRENCY
import com.monster0506.divvy.data.dto.PersonDto
import com.monster0506.divvy.platform.normalizeVenmoUsername
import com.monster0506.divvy.platform.venmoUsernameProblem
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.domain.simplifyDebts
import com.monster0506.divvy.ui.model.initialsFor
import com.monster0506.divvy.ui.model.toMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BalancesPhase { Idle, Loading, Ready, Failed }

/**
 * One payment that would settle part of the household: [from] pays [to]. [involvesMe] is true for this device's
 * person. [toVenmo] is where [to] receives payments, if they have said.
 */
data class SettleTransfer(
    val from: Person,
    val to: Person,
    val amountCents: Int,
    val involvesMe: Boolean = false,
    val toVenmo: String? = null,
    /** True when this device's person is the one paying, the only case Venmo is offered. */
    val iPay: Boolean = false,
)

/** The note Venmo shows on a settle-up payment. */
const val VENMO_NOTE = "Divvy settle-up"

/** A payment just recorded, which [BalancesController.undo] can reverse for a short while. */
data class Payment(val from: Person, val to: Person, val amountCents: Int)

data class BalancesState(
    val phase: BalancesPhase = BalancesPhase.Idle,
    /** Everyone with a balance, the person owed the most first. */
    val balances: List<Balance> = emptyList(),
    /** True while a reload runs on top of balances already shown. */
    val refreshing: Boolean = false,
    /** Why the last load failed. Balances from an earlier load stay on screen beside it. */
    val message: String? = null,
    /** The server reported more than one currency; only dollars (or else the first) are shown. */
    val otherCurrencies: Boolean = false,
    /** The fewest payments that would settle everyone, the ones involving this device's person first. */
    val transfers: List<SettleTransfer> = emptyList(),
    /** True while a payment is being recorded. */
    val recording: Boolean = false,
    /** The payment just recorded, offered for undo until the window passes. */
    val payment: Payment? = null,
    /** True while a Venmo username is being saved. */
    val savingVenmo: Boolean = false,
    /** Why the last Venmo username could not be saved. */
    val venmoMessage: String? = null,
) {
    val settled get() = phase == BalancesPhase.Ready && balances.all { it.netCents == 0 }
}

/** Loads a household's balances from the server and keeps the last good answer if a reload fails. */
class BalancesController(
    private val load: suspend (householdId: String) -> ApiResult<BalancesDto>,
    private val scope: CoroutineScope,
    private val record: suspend (householdId: String, request: CreateSettlementRequest) -> ApiResult<SettlementDto> =
        { _, _ -> ApiResult.Failure(ApiFailure.Offline) },
    /** The person this device last acted as in the household, to put their payments first. */
    private val currentPerson: (householdId: String) -> String? = { null },
    private val undoWindowMillis: Long = 8_000,
    /** Saves where a person receives payments, as Household settings does. */
    private val saveVenmo: suspend (householdId: String, personId: String, username: String) -> ApiResult<PersonDto> =
        { _, _, _ -> ApiResult.Failure(ApiFailure.Offline) },
) {
    private val mutableState = MutableStateFlow(BalancesState())
    val state: StateFlow<BalancesState> = mutableState

    private var householdId: String? = null
    private var job: Job? = null
    private var undoJob: Job? = null

    /** Shows [householdId]'s balances, loading them afresh. */
    fun open(householdId: String) {
        if (this.householdId != householdId) mutableState.value = BalancesState()
        this.householdId = householdId
        refresh()
    }

    fun refresh() {
        val id = householdId ?: return
        job?.cancel()
        mutableState.update {
            if (it.phase == BalancesPhase.Ready) it.copy(refreshing = true, message = null)
            else it.copy(phase = BalancesPhase.Loading, message = null)
        }
        job = scope.launch {
            val result = try {
                load(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApiResult.Failure(ApiFailure.ServerError(null))
            }
            when (result) {
                is ApiResult.Success -> mutableState.update {
                    result.value.toState(id).copy(
                        payment = it.payment,
                        recording = it.recording,
                        savingVenmo = it.savingVenmo,
                        venmoMessage = it.venmoMessage,
                    )
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(
                        phase = if (it.phase == BalancesPhase.Ready) BalancesPhase.Ready else BalancesPhase.Failed,
                        refreshing = false,
                        message = describe(result.failure),
                    )
                }
            }
        }
    }

    /** Records that [transfer]'s payer paid [amountCents] (all of it by default, or part), then reloads the balances. */
    fun settle(transfer: SettleTransfer, amountCents: Int = transfer.amountCents) {
        val id = householdId ?: return
        if (mutableState.value.recording) return
        if (amountCents < 1 || amountCents > transfer.amountCents) {
            mutableState.update { it.copy(message = "Enter an amount between ${1.toMoney()} and ${transfer.amountCents.toMoney()}.") }
            return
        }
        mutableState.update { it.copy(recording = true, message = null) }
        scope.launch {
            val request = CreateSettlementRequest(transfer.from.id, transfer.to.id, amountCents.toLong())
            when (val result = safely { record(id, request) }) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(recording = false, payment = Payment(transfer.from, transfer.to, amountCents)) }
                    refresh()
                    startUndoWindow()
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(recording = false, message = "Could not record that payment. ${describe(result.failure)}")
                }
            }
        }
    }

    /** Reverses the payment just recorded by recording the same amount back the other way. */
    fun undo() {
        val id = householdId ?: return
        val payment = mutableState.value.payment ?: return
        if (mutableState.value.recording) return
        undoJob?.cancel()
        mutableState.update { it.copy(recording = true, message = null) }
        scope.launch {
            val request = CreateSettlementRequest(payment.to.id, payment.from.id, payment.amountCents.toLong(), note = "Undo")
            when (val result = safely { record(id, request) }) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(recording = false, payment = null) }
                    refresh()
                }
                is ApiResult.Failure -> {
                    mutableState.update {
                        it.copy(recording = false, message = "Could not undo that payment. ${describe(result.failure)}")
                    }
                    startUndoWindow()
                }
            }
        }
    }

    /**
     * Saves [username] as where [person] receives payments, then shows Pay with Venmo on their payments.
     * [onSaved] runs once the server has it.
     */
    fun setVenmo(person: Person, username: String, onSaved: () -> Unit = {}) {
        val id = householdId ?: return
        if (mutableState.value.savingVenmo) return
        venmoUsernameProblem(username)?.let { problem ->
            mutableState.update { it.copy(venmoMessage = problem) }
            return
        }
        mutableState.update { it.copy(savingVenmo = true, venmoMessage = null) }
        scope.launch {
            when (val result = safely { saveVenmo(id, person.id, normalizeVenmoUsername(username)) }) {
                is ApiResult.Success -> {
                    val saved = result.value.venmoUsername?.takeIf { it.isNotBlank() }
                    mutableState.update { s ->
                        s.copy(
                            savingVenmo = false,
                            transfers = s.transfers.map { if (it.to.id == person.id) it.copy(toVenmo = saved) else it },
                        )
                    }
                    onSaved()
                }
                is ApiResult.Failure -> mutableState.update {
                    it.copy(savingVenmo = false, venmoMessage = venmoFailure(result.failure))
                }
            }
        }
    }

    fun dismissVenmoMessage() {
        mutableState.update { it.copy(venmoMessage = null) }
    }

    fun dismissPayment() {
        undoJob?.cancel()
        mutableState.update { it.copy(payment = null) }
    }

    private fun startUndoWindow() {
        undoJob?.cancel()
        undoJob = scope.launch {
            delay(undoWindowMillis)
            mutableState.update { it.copy(payment = null) }
        }
    }

    private suspend fun <T> safely(call: suspend () -> ApiResult<T>): ApiResult<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(ApiFailure.ServerError(null))
        }

    private fun BalancesDto.toState(householdId: String): BalancesState {
        val shown = balances.firstOrNull { it.currency == DEFAULT_CURRENCY } ?: balances.firstOrNull()
        val people = shown?.people.orEmpty()
        val ui = people.mapIndexed { index, p ->
            Person(p.personId, p.displayName, initialsFor(p.displayName), AvatarColor.entries[index % AvatarColor.entries.size])
        }
        val me = currentPerson(householdId)
        return BalancesState(
            phase = BalancesPhase.Ready,
            balances = people.mapIndexed { index, p -> Balance(ui[index], p.netMinor.toInt()) }
                .sortedWith(compareByDescending<Balance> { it.netCents }.thenBy { it.person.name }),
            otherCurrencies = balances.size > 1,
            transfers = transfersFor(
                net = people.map { it.personId to it.netMinor },
                people = ui,
                venmo = people.mapNotNull { p -> p.venmoUsername?.takeIf { it.isNotBlank() }?.let { p.personId to it } }.toMap(),
                me = me,
            ),
        )
    }

    private fun transfersFor(
        net: List<Pair<String, Long>>,
        people: List<Person>,
        venmo: Map<String, String>,
        me: String?,
    ): List<SettleTransfer> {
        if (net.sumOf { it.second } != 0L) return emptyList()
        val byId = people.associateBy { it.id }
        return simplifyDebts(net.toMap()).mapNotNull { t ->
            val from = byId[t.fromPersonId] ?: return@mapNotNull null
            val to = byId[t.toPersonId] ?: return@mapNotNull null
            SettleTransfer(
                from,
                to,
                t.amountCents.toInt(),
                involvesMe = me != null && (from.id == me || to.id == me),
                toVenmo = venmo[to.id],
                iPay = me != null && from.id == me,
            )
        }.sortedWith(compareByDescending<SettleTransfer> { it.involvesMe }.thenByDescending { it.amountCents })
    }

    private fun venmoFailure(failure: ApiFailure): String = when (failure) {
        is ApiFailure.Validation -> failure.error.message
        is ApiFailure.ServerError -> "The server could not save that Venmo username. Try again in a moment."
        else -> describe(failure)
    }

    private fun describe(failure: ApiFailure): String = when (failure) {
        ApiFailure.Offline -> "Could not reach the server. Check the URL in Account > Server and try again."
        ApiFailure.Unauthorised -> "The server rejected the API key. Check Account > Server."
        is ApiFailure.Validation ->
            if (failure.error.code == "not_found") "This household was not found on the server." else failure.error.message
        is ApiFailure.ServerError -> "The server had a problem loading balances. Try again in a moment."
    }
}
