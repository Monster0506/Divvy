package com.monster0506.divvy.settings

import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.MlSettingsDto
import com.monster0506.divvy.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The calls the auto-assign setting needs, so it can be tested without a network. */
interface SettingsApi {
    suspend fun load(householdId: String): ApiResult<MlSettingsDto>

    suspend fun save(householdId: String, settings: MlSettingsDto): ApiResult<MlSettingsDto>
}

class RepositorySettingsApi(private val settings: SettingsRepository) : SettingsApi {
    override suspend fun load(householdId: String) = settings.autoAssign(householdId)

    override suspend fun save(householdId: String, settings: MlSettingsDto) =
        this.settings.saveAutoAssign(householdId, settings)
}

/** The household's auto-assign switch and confidence threshold; null until the server has answered. */
data class AutoAssignState(
    val autoAssign: Boolean? = null,
    val threshold: Float? = null,
    /** Why the last save or load failed. Cleared by the next one that works. */
    val message: String? = null,
)

/**
 * Keeps the household's auto-assign setting in step with the server. A change shows at once and is saved after a
 * short pause, so dragging the threshold sends one request; if the server refuses it the last saved value comes back.
 */
class AutoAssignController(
    private val api: SettingsApi,
    private val scope: CoroutineScope,
    private val saveDelayMillis: Long = 600,
) {
    private val mutableState = MutableStateFlow(AutoAssignState())
    val state: StateFlow<AutoAssignState> = mutableState

    private var householdId = ""
    private var saved: MlSettingsDto? = null
    private var pending: Job? = null

    fun load(householdId: String) {
        this.householdId = householdId
        pending?.cancel()
        scope.launch {
            when (val result = api.load(householdId)) {
                is ApiResult.Success -> {
                    saved = result.value
                    mutableState.value = result.value.toState()
                }
                is ApiResult.Failure -> mutableState.update { it.copy(message = "Could not load the auto-assign setting.") }
            }
        }
    }

    /** Shows the new values now and saves them once changes stop. */
    fun change(autoAssign: Boolean, threshold: Float) {
        if (householdId.isEmpty()) return
        val clamped = threshold.coerceIn(0f, 1f)
        mutableState.update { it.copy(autoAssign = autoAssign, threshold = clamped) }
        pending?.cancel()
        pending = scope.launch {
            delay(saveDelayMillis)
            val request = MlSettingsDto(autoAssign, clamped.toDouble())
            when (val result = api.save(householdId, request)) {
                is ApiResult.Success -> {
                    saved = result.value
                    mutableState.value = result.value.toState()
                }
                is ApiResult.Failure -> {
                    val before = saved
                    mutableState.value = (before?.toState() ?: AutoAssignState())
                        .copy(message = "Could not save the auto-assign setting.")
                }
            }
        }
    }

    fun reset() {
        pending?.cancel()
        householdId = ""
        saved = null
        mutableState.value = AutoAssignState()
    }
}

private fun MlSettingsDto.toState() = AutoAssignState(autoAssign, autoAssignThreshold.toFloat())
