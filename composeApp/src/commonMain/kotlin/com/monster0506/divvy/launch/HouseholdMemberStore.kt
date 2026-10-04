package com.monster0506.divvy.launch

import com.russhwolf.settings.Settings

/** Remembers which household member represents this device, until sign-in provides a durable identity. */
class HouseholdMemberStore(private val settings: Settings) {
    fun get(householdId: String): String? = settings.getStringOrNull(key(householdId))

    fun save(householdId: String, personId: String) {
        settings.putString(key(householdId), personId)
    }

    private fun key(householdId: String) = "me_in_household_$householdId"
}
