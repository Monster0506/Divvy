package com.monster0506.divvy.domain

import com.monster0506.divvy.ui.model.Person
import com.monster0506.divvy.ui.model.ReceiptDraft
import com.monster0506.divvy.ui.model.ReceiptLine
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitWeightsTest {
    private val a = Person("a", "Ann", "An")
    private val b = Person("b", "Ben", "Be")
    private val c = Person("c", "Cy", "Cy")

    private fun draft(vararg lines: ReceiptLine, tax: Int = 0) =
        ReceiptDraft("Shop", "", a, listOf(a, b, c), lines.toList(), tax)

    private fun line(price: Int, owners: Set<String>, weights: Map<String, Int> = emptyMap()) =
        ReceiptLine(1, "item", price, owners, 1f, true, weights)

    private fun ReceiptDraft.owed() = shares().associate { (p, cents) -> p.id to cents }

    @Test
    fun uneven_weights_change_the_shares() {
        val owed = draft(line(900, setOf("a", "b"), mapOf("a" to 2, "b" to 1))).owed()
        assertEquals(600, owed["a"])
        assertEquals(300, owed["b"])
    }

    @Test
    fun default_weights_split_evenly_with_odd_cents_to_the_first_people() {
        val owed = draft(line(100, setOf("a", "b", "c"))).owed()
        assertEquals(listOf(34, 33, 33), listOf(owed["a"], owed["b"], owed["c"]))
    }

    @Test
    fun a_line_s_shares_always_add_up_to_its_price() {
        val d = draft(line(1001, setOf("a", "b", "c"), mapOf("a" to 3, "b" to 5, "c" to 1)))
        assertEquals(1001, d.shares().sumOf { it.second })
    }

    @Test
    fun tax_follows_the_weighted_items() {
        val d = draft(line(1000, setOf("a", "b"), mapOf("a" to 3, "b" to 1)), tax = 100)
        val owed = d.owed()
        assertEquals(825, owed["a"])
        assertEquals(275, owed["b"])
    }
}
