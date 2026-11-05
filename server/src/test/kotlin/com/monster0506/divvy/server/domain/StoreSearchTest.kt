package com.monster0506.divvy.server.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreSearchTest {
    private fun finds(typed: String, name: String) = assertTrue(StoreSearch(typed).matches(name), "\"$typed\" should find \"$name\"")
    private fun misses(typed: String, name: String) = assertFalse(StoreSearch(typed).matches(name), "\"$typed\" should not find \"$name\"")

    @Test
    fun `any part of the name matches, ignoring case, spaces and punctuation`() {
        finds("cost", "COSTCO WHOLESALE")
        finds("WHOLE", "COSTCO WHOLESALE")
        finds("co whole", "COSTCO WHOLESALE")
        finds("costcowholesale", "COSTCO WHOLESALE")
        finds("trader joes", "Trader Joe's")
        finds("Trader-Joe's", "Trader Joe's")
        finds("café", "Café Nero")
        misses("target", "COSTCO WHOLESALE")
    }

    @Test
    fun `a star stands for anything, keeping the pieces in order`() {
        finds("cost*sale", "COSTCO WHOLESALE")
        finds("tr*joe", "Trader Joe's")
        finds("*sale", "COSTCO WHOLESALE")
        finds("*", "Aldi")
        misses("sale*cost", "COSTCO WHOLESALE")
        // A star turns typo tolerance off: what is typed around it must be there.
        misses("cosco*", "COSTCO WHOLESALE")
    }

    @Test
    fun `longer searches allow a typo`() {
        finds("walmrt", "Walmart Supercenter")
        finds("cosco", "COSTCO WHOLESALE")
        finds("tagret", "Target")
        finds("wallmart", "Walmart")
        finds("costco wholsale", "COSTCO WHOLESALE")
        // Two typos only from eight characters up.
        finds("tradr jos", "Trader Joe's")
        misses("kostko", "COSTCO WHOLESALE")
    }

    @Test
    fun `short searches and near misses do not match`() {
        // Three characters is too short to guess at.
        misses("ald", "Lidl")
        misses("tar", "Car Wash")
        // "costco" is two typos from "Cost Plus", too many for six characters.
        misses("costco", "Cost Plus World Market")
        misses("aldi", "Lidl")
    }

    @Test
    fun `a search with no letters or digits is looked for as typed`() {
        finds("%", "100% Juice")
        misses("%", "Aldi")
        misses("_", "Aldi")
        // Wildcard characters in a search with letters are just ignored like other punctuation.
        misses("a_d", "Aldi")
    }
}
