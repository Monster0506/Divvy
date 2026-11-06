package com.monster0506.divvy.launch

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.monster0506.divvy.data.dto.ApiError
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LaunchRouterTest {
    private fun router(settings: MapSettings, result: LookupResult = LookupResult.Found) =
        LaunchRouter(HouseholdStore(settings)) { result }

    @Test
    fun `first launch shows onboarding`() = runTest {
        assertEquals(LaunchDestination.Onboarding, router(MapSettings()).decide())
    }

    @Test
    fun `relaunch after onboarding opens the household`() = runTest {
        val settings = MapSettings()
        router(settings).onboardingCompleted("household-1")

        assertEquals(LaunchDestination.Home, router(settings).decide())
    }

    @Test
    fun `a stored household that no longer exists goes back to onboarding`() = runTest {
        val settings = MapSettings()
        router(settings).onboardingCompleted("household-1")

        val relaunch = router(settings, LookupResult.NotFound)
        assertEquals(LaunchDestination.Onboarding, relaunch.decide())
        assertNull(HouseholdStore(settings).get())
    }

    @Test
    fun `an unreachable server keeps the stored household and opens home`() = runTest {
        val settings = MapSettings()
        router(settings).onboardingCompleted("household-1")

        assertEquals(LaunchDestination.Home, router(settings, LookupResult.Unavailable).decide())
        assertEquals("household-1", HouseholdStore(settings).get())
    }

    @Test
    fun `a household saved by a build that ran without a server goes to onboarding`() = runTest {
        val settings = MapSettings()
        HouseholdStore(settings).save("local")

        assertNull(router(settings).currentHouseholdId())
        assertEquals(LaunchDestination.Onboarding, router(settings).decide())
        assertNull(HouseholdStore(settings).get())
    }

    @Test
    fun `the server lookup tells a deleted household from one it cannot check`() = runTest {
        suspend fun result(failure: ApiFailure?) =
            ServerHouseholdLookup { if (failure != null) throw ApiException(failure) }.lookup("h1")

        assertEquals(LookupResult.Found, result(null))
        assertEquals(LookupResult.NotFound, result(ApiFailure.Validation(ApiError("not_found", "household not found"))))
        assertEquals(LookupResult.Unavailable, result(ApiFailure.Offline))
        assertEquals(LookupResult.Unavailable, result(ApiFailure.Unauthorised))
    }
}