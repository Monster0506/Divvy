package com.monster0506.divvy.launch

import com.russhwolf.settings.Settings

/** Remembers which household this device belongs to. */
class HouseholdStore(private val settings: Settings) {
    fun get(): String? = settings.getStringOrNull(KEY)

    fun save(householdId: String) {
        settings.putString(KEY, householdId)
    }

    fun clear() {
        settings.remove(KEY)
    }

    private companion object {
        const val KEY = "current_household_id"
    }
}

sealed interface LookupResult {
    data object Found : LookupResult
    data object NotFound : LookupResult
    data object Unavailable : LookupResult
}

/** Checks that a stored household still exists. Swap in a server-backed version when the API client exists. */
fun interface HouseholdLookup {
    suspend fun lookup(householdId: String): LookupResult
}

/** Placeholder until the app talks to the server: every stored household counts as found. */
class LocalHouseholdLookup : HouseholdLookup {
    override suspend fun lookup(householdId: String): LookupResult = LookupResult.Found
}

enum class LaunchDestination { Onboarding, Home }

const val LOCAL_HOUSEHOLD_ID = "local"

class LaunchRouter(private val store: HouseholdStore, private val lookup: HouseholdLookup) {
    suspend fun decide(): LaunchDestination {
        val id = store.get() ?: return LaunchDestination.Onboarding
        return when (lookup.lookup(id)) {
            LookupResult.Found, LookupResult.Unavailable -> LaunchDestination.Home
            LookupResult.NotFound -> {
                store.clear()
                LaunchDestination.Onboarding
            }
        }
    }

    /** Call when onboarding finishes so the next launch opens the household. */
    fun onboardingCompleted(householdId: String = LOCAL_HOUSEHOLD_ID) = store.save(householdId)
}