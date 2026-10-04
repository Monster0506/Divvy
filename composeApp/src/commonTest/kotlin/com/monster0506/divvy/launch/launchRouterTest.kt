package com.monster0506.divvy.launch

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
}