package com.monster0506.divvy.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateMillisTest {
    @Test
    fun known_days_convert_to_their_iso_date() {
        assertEquals("1970-01-01", isoDateFromUtcMillis(0))
        assertEquals("2026-01-01", isoDateFromUtcMillis(1_767_225_600_000))
        assertEquals("2024-02-29", isoDateFromUtcMillis(1_709_164_800_000))
    }

    @Test
    fun an_iso_date_round_trips() {
        listOf("1999-12-31", "2000-02-29", "2026-09-01", "2026-12-31", "2027-03-05").forEach { iso ->
            assertEquals(iso, isoDateFromUtcMillis(utcMillisFromIso(iso)!!))
        }
    }

    @Test
    fun something_that_is_not_a_date_gives_nothing() {
        assertNull(utcMillisFromIso("sometime"))
        assertNull(utcMillisFromIso("2026-13-01"))
    }
}