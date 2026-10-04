package com.monster0506.divvy.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DebouncedSaverTest {
    @Test
    fun only_the_latest_value_is_saved_after_a_quiet_period() = runTest {
        val saved = mutableListOf<Pair<Int, Set<String>>>()
        val saver = DebouncedSaver<Int, Set<String>>(this, 400) { k, v -> saved += k to v }

        saver.submit(1, setOf("a"))
        delay(200)
        saver.submit(1, setOf("a", "b"))
        delay(1000)

        assertEquals(listOf(1 to setOf("a", "b")), saved)
    }

    @Test
    fun different_keys_are_saved_independently() = runTest {
        val saved = mutableListOf<Int>()
        val saver = DebouncedSaver<Int, Set<String>>(this, 400) { k, _ -> saved += k }

        saver.submit(1, setOf("a"))
        saver.submit(2, setOf("b"))
        delay(1000)

        assertEquals(listOf(1, 2), saved)
    }
}
