package com.monster0506.divvy.launch

import com.monster0506.divvy.data.api.ApiException
import com.monster0506.divvy.data.api.ApiFailure
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException

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

/** Checks that a stored household still exists. */
fun interface HouseholdLookup {
    suspend fun lookup(householdId: String): LookupResult
}

/**
 * Asks the server for the household with [get]. A household the server says is gone is [LookupResult.NotFound];
 * anything else that fails (no connection, a rejected key) is [LookupResult.Unavailable], so the household is kept.
 */
class ServerHouseholdLookup(private val get: suspend (householdId: String) -> Unit) : HouseholdLookup {
    override suspend fun lookup(householdId: String): LookupResult = try {
        get(householdId)
        LookupResult.Found
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        val failure = e.failure
        if (failure is ApiFailure.Validation && failure.error.code == "not_found") LookupResult.NotFound else LookupResult.Unavailable
    } catch (e: Exception) {
        LookupResult.Unavailable
    }
}

enum class LaunchDestination { Onboarding, Home }

class LaunchRouter(private val store: HouseholdStore, private val lookup: HouseholdLookup) {
    fun currentHouseholdId(): String? = store.get()?.takeIf { it.isNotBlank() && it != LEGACY_LOCAL_ID }

    suspend fun decide(): LaunchDestination {
        val id = currentHouseholdId() ?: return LaunchDestination.Onboarding.also { store.clear() }
        return when (lookup.lookup(id)) {
            LookupResult.Found, LookupResult.Unavailable -> LaunchDestination.Home
            LookupResult.NotFound -> {
                store.clear()
                LaunchDestination.Onboarding
            }
        }
    }

    /** Call when onboarding finishes so the next launch opens the household. */
    fun onboardingCompleted(householdId: String) = store.save(householdId)

    private companion object {
        /** Early builds could run without a server and saved this id; such a phone sets up a real household. */
        const val LEGACY_LOCAL_ID = "local"
    }
}
