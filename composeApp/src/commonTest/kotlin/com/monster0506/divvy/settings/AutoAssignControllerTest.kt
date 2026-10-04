package com.monster0506.divvy.settings

import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.api.ApiResult
import com.monster0506.divvy.data.dto.MlSettingsDto
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeSettings : SettingsApi {
    var loaded: ApiResult<MlSettingsDto> = ApiResult.Success(MlSettingsDto(true, 0.85))
    var saveFailure: ApiFailure? = null
    val saves = mutableListOf<Pair<String, MlSettingsDto>>()

    override suspend fun load(householdId: String) = loaded

    override suspend fun save(householdId: String, settings: MlSettingsDto): ApiResult<MlSettingsDto> {
        saves += householdId to settings
        return saveFailure?.let { ApiResult.Failure(it) } ?: ApiResult.Success(settings)
    }
}

class AutoAssignControllerTest {
    private fun TestScope.controller(api: FakeSettings) = AutoAssignController(api, this, saveDelayMillis = 500)

    @Test
    fun the_servers_setting_is_loaded() = runTest {
        val api = FakeSettings().apply { loaded = ApiResult.Success(MlSettingsDto(false, 0.7)) }
        val c = controller(api)

        c.load("h1")
        advanceUntilIdle()

        assertEquals(AutoAssignState(false, 0.7f), c.state.value)
    }

    @Test
    fun a_failed_load_says_so_and_leaves_the_values_unknown() = runTest {
        val api = FakeSettings().apply { loaded = ApiResult.Failure(ApiFailure.Offline) }
        val c = controller(api)

        c.load("h1")
        advanceUntilIdle()

        assertNull(c.state.value.autoAssign)
        assertEquals("Could not load the auto-assign setting.", c.state.value.message)
    }

    @Test
    fun a_change_shows_at_once_and_is_saved_after_a_pause() = runTest {
        val api = FakeSettings()
        val c = controller(api)
        c.load("h1")
        advanceUntilIdle()

        c.change(true, 0.9f)
        assertEquals(0.9f, c.state.value.threshold)
        runCurrent()
        assertEquals(0, api.saves.size)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf("h1" to MlSettingsDto(true, 0.9f.toDouble())), api.saves)
    }

    @Test
    fun dragging_the_threshold_sends_one_request_with_the_last_value() = runTest {
        val api = FakeSettings()
        val c = controller(api)
        c.load("h1")
        advanceUntilIdle()

        c.change(true, 0.6f)
        advanceTimeBy(200)
        c.change(true, 0.7f)
        advanceTimeBy(200)
        c.change(true, 0.8f)
        advanceUntilIdle()

        assertEquals(1, api.saves.size)
        assertEquals(0.8f.toDouble(), api.saves.single().second.autoAssignThreshold)
    }

    @Test
    fun a_refused_save_puts_the_last_saved_value_back_with_a_message() = runTest {
        val api = FakeSettings()
        val c = controller(api)
        c.load("h1")
        advanceUntilIdle()
        api.saveFailure = ApiFailure.ServerError(500)

        c.change(false, 0.5f)
        advanceUntilIdle()

        assertEquals(true, c.state.value.autoAssign)
        assertEquals(0.85f, c.state.value.threshold)
        assertEquals("Could not save the auto-assign setting.", c.state.value.message)
    }

    @Test
    fun the_threshold_is_kept_between_zero_and_one() = runTest {
        val api = FakeSettings()
        val c = controller(api)
        c.load("h1")
        advanceUntilIdle()

        c.change(true, 1.7f)
        advanceUntilIdle()

        assertEquals(1.0, api.saves.single().second.autoAssignThreshold)
    }

    @Test
    fun nothing_is_sent_before_a_household_is_known_and_reset_forgets_it() = runTest {
        val api = FakeSettings()
        val c = controller(api)

        c.change(true, 0.9f)
        advanceUntilIdle()
        assertEquals(0, api.saves.size)

        c.load("h1")
        advanceUntilIdle()
        c.reset()
        c.change(true, 0.9f)
        advanceUntilIdle()

        assertEquals(0, api.saves.size)
        assertNull(c.state.value.autoAssign)
    }
}
