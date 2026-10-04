package com.monster0506.divvy.data.api

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiConfigStoreTest {
    @Test
    fun savedSettingsOverrideBuildDefaults() {
        val store = ApiConfigStore(MapSettings())
        val defaults = ApiConfig("http://localhost:8080", "dev-key")
        assertEquals(defaults, store.load(defaults))

        val saved = ApiConfig("https://api.example.com", "other-key")
        store.save(saved)
        assertEquals(saved, store.load(defaults))
    }

    @Test
    fun acceptsOnlyAbsoluteHttpAddressesAndNonemptyKeys() {
        assertEquals(
            ApiConfig("https://api.example.com", "key"),
            ApiConfig(" https://api.example.com/ ", " key ").normalizedOrNull(),
        )
        assertNull(ApiConfig("localhost:8080", "key").normalizedOrNull())
        assertNull(ApiConfig("ftp://api.example.com", "key").normalizedOrNull())
        assertNull(ApiConfig("https://api.example.com?x=1", "key").normalizedOrNull())
        assertNull(ApiConfig("https://api.example.com", " ").normalizedOrNull())
    }
}
