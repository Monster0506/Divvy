package com.monster0506.divvy.review

import com.russhwolf.settings.Settings

/** Remembers who paid the last receipt in each household on this device, to preselect them next time. */
class PayerStore(private val settings: Settings) {
    fun get(householdId: String): String? = settings.getStringOrNull(key(householdId))

    fun save(householdId: String, personId: String) {
        settings.putString(key(householdId), personId)
    }

    private fun key(householdId: String) = "last_payer_$householdId"
}
