package com.monster0506.divvy.review

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayerStoreTest {
    @Test
    fun remembersTheLastPayerPerHousehold() {
        val store = PayerStore(MapSettings())
        assertNull(store.get("h1"))

        store.save("h1", "p1")
        store.save("h2", "p9")
        store.save("h1", "p2")

        assertEquals("p2", store.get("h1"))
        assertEquals("p9", store.get("h2"))
    }
}
