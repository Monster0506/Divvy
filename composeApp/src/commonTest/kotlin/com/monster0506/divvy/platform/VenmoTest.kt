package com.monster0506.divvy.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VenmoTest {
    @Test
    fun deep_link_has_recipient_amount_and_note() {
        assertEquals(
            "venmo://paycharge?txn=pay&recipients=sam&amount=12.50&note=Groceries",
            venmoDeepLink("sam", 1250, "Groceries"),
        )
    }

    @Test
    fun note_is_percent_encoded() {
        val link = venmoDeepLink("sam", 5, "Milk & eggs #1")
        assertEquals("venmo://paycharge?txn=pay&recipients=sam&amount=0.05&note=Milk%20%26%20eggs%20%231", link)
    }

    @Test
    fun leading_at_sign_and_spaces_are_removed() {
        assertEquals(venmoDeepLink("sam", 100, "x"), venmoDeepLink(" @sam ", 100, "x"))
    }

    @Test
    fun web_url_puts_username_in_the_path() {
        assertEquals(
            "https://venmo.com/sam?txn=pay&amount=3.00&note=Dinner",
            venmoWebUrl("sam", 300, "Dinner"),
        )
    }

    @Test
    fun pay_page_has_recipient_amount_and_note() {
        assertEquals(
            "https://account.venmo.com/pay?txn=pay&recipients=Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoPayUrl("@Savar-Shrestha", 1250, "Divvy settle-up"),
        )
    }

    @Test
    fun request_page_charges_the_payer() {
        assertEquals(
            "https://account.venmo.com/pay?txn=charge&recipients=Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoRequestUrl("@Savar-Shrestha", 1250, "Divvy settle-up"),
        )
    }

    @Test
    fun usernames_follow_venmo_rules() {
        listOf("Savar-Shrestha", "savar-shrestha", "sam-r", "Sam_Lee-7", " @sam_r ", "a".repeat(30), "", "  ").forEach {
            assertNull(venmoUsernameProblem(it), it)
        }
        assertEquals("Venmo usernames are at least 5 characters.", venmoUsernameProblem("@sam"))
        assertEquals("Venmo usernames are at most 30 characters.", venmoUsernameProblem("a".repeat(31)))
        listOf("sam r", "sam.r", "sam!r", "@@samr").forEach {
            assertEquals("Use only letters, numbers, - and _.", venmoUsernameProblem(it), it)
        }
        assertEquals("sam_r", normalizeVenmoUsername(" @sam_r "))
    }

    @Test
    fun rejects_non_positive_amount_and_blank_username() {
        assertFailsWith<IllegalArgumentException> { venmoDeepLink("sam", 0, "x") }
        assertFailsWith<IllegalArgumentException> { venmoWebUrl("@", 100, "x") }
    }
}
