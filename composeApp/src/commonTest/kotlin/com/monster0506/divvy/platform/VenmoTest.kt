package com.monster0506.divvy.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VenmoTest {
    @Test
    fun deep_link_pays_the_at_username_with_amount_and_note() {
        assertEquals(
            "venmo://paycharge?txn=pay&recipients=%40Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoDeepLink("Savar-Shrestha", 1250, "Divvy settle-up"),
        )
    }

    @Test
    fun deep_link_request_charges_the_payer() {
        assertEquals(
            "venmo://paycharge?txn=charge&recipients=%40Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoDeepLink("Savar-Shrestha", 1250, "Divvy settle-up", txn = "charge"),
        )
    }

    @Test
    fun note_is_percent_encoded() {
        val link = venmoDeepLink("sam-r", 5, "Milk & eggs #1")
        assertEquals("venmo://paycharge?txn=pay&recipients=%40sam-r&amount=0.05&note=Milk%20%26%20eggs%20%231", link)
    }

    @Test
    fun a_typed_at_sign_and_spaces_are_not_doubled() {
        assertEquals(venmoDeepLink("sam-r", 100, "x"), venmoDeepLink(" @sam-r ", 100, "x"))
    }

    @Test
    fun pay_page_fallback_has_recipient_amount_and_note() {
        assertEquals(
            "https://account.venmo.com/pay?txn=pay&recipients=Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoPayUrl("@Savar-Shrestha", 1250, "Divvy settle-up"),
        )
        assertEquals(
            "https://account.venmo.com/pay?txn=charge&recipients=Savar-Shrestha&amount=12.50&note=Divvy%20settle-up",
            venmoPayUrl("Savar-Shrestha", 1250, "Divvy settle-up", txn = "charge"),
        )
    }

    @Test
    fun the_app_link_is_tried_first_then_the_pay_page() {
        val opened = mutableListOf<String>()
        val opener = UrlOpener { url, onFailure -> opened += url; if (url.startsWith("venmo://")) onFailure() }

        opener.payWithVenmo("Savar-Shrestha", 1250, "n")
        opener.requestWithVenmo("Savar-Shrestha", 1250, "n")

        assertEquals(
            listOf(
                "venmo://paycharge?txn=pay&recipients=%40Savar-Shrestha&amount=12.50&note=n",
                "https://account.venmo.com/pay?txn=pay&recipients=Savar-Shrestha&amount=12.50&note=n",
                "venmo://paycharge?txn=charge&recipients=%40Savar-Shrestha&amount=12.50&note=n",
                "https://account.venmo.com/pay?txn=charge&recipients=Savar-Shrestha&amount=12.50&note=n",
            ),
            opened,
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
        assertFailsWith<IllegalArgumentException> { venmoPayUrl("@", 100, "x") }
    }
}
