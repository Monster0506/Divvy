package com.monster0506.divvy.balances

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.BalancesDto
import com.monster0506.divvy.data.dto.DEFAULT_CURRENCY
import com.monster0506.divvy.ui.model.AvatarColor
import com.monster0506.divvy.ui.model.Balance
import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.initialsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BalancesPhase { Idle, Loading, Ready, Failed }

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
) {
    val settled get() = phase == BalancesPhase.Ready && balances.all { it.netCents == 0 }
}

/** Loads a household's balances from the server and keeps the last good answer if a reload fails. */
class BalancesController(
    private val load: suspend (householdId: String) -> ApiResult<BalancesDto>,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(BalancesState())
    val state: StateFlow<BalancesState> = mutableState

    private var householdId: String? = null
    private var job: Job? = null

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
                is ApiResult.Success -> mutableState.value = result.value.toState()
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

    private fun BalancesDto.toState(): BalancesState {
        val shown = balances.firstOrNull { it.currency == DEFAULT_CURRENCY } ?: balances.firstOrNull()
        val people = shown?.people.orEmpty()
        return BalancesState(
            phase = BalancesPhase.Ready,
            balances = people.mapIndexed { index, p ->
                val color = AvatarColor.entries[index % AvatarColor.entries.size]
                Balance(Person(p.personId, p.displayName, initialsFor(p.displayName), color), p.netMinor.toInt())
            }.sortedWith(compareByDescending<Balance> { it.netCents }.thenBy { it.person.name }),
            otherCurrencies = balances.size > 1,
        )
    }

    private fun describe(failure: ApiFailure): String = when (failure) {
        ApiFailure.Offline -> "Could not reach the server. Check the URL in Account > Server and try again."
        ApiFailure.Unauthorised -> "The server rejected the API key. Check Account > Server."
        is ApiFailure.Validation ->
            if (failure.error.code == "not_found") "This household was not found on the server." else failure.error.message
        is ApiFailure.ServerError -> "The server had a problem loading balances. Try again in a moment."
    }
}
