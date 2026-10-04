package com.monster0506.divvy.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateInputTest {
    @Test
    fun parses_supported_date_forms_to_iso() {
        assertEquals("2026-09-18", parseDateInput("2026-09-18"))
        assertEquals("2026-09-18", parseDateInput("9/18/2026"))
        assertEquals("2026-09-18", parseDateInput("09/18/26"))
    }

    @Test
    fun rejects_impossible_or_out_of_range_dates() {
        assertNull(parseDateInput("2025-02-29"))
        assertNull(parseDateInput("13/1/2026"))
        assertNull(parseDateInput("1/1/2101"))
        assertNull(parseDateInput("tomorrow"))
    }
}
