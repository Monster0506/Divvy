package com.monster0506.divvy.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DebtSimplifierTest {
    @Test
    fun empty_and_all_zero_need_no_transfers() {
        assertEquals(emptyList(), simplifyDebts(emptyMap()))
        assertEquals(emptyList(), simplifyDebts(mapOf("a" to 0L, "b" to 0L)))
    }

    @Test
    fun single_debt_is_one_transfer() {
        assertEquals(
            listOf(Transfer("b", "a", 500)),
            simplifyDebts(mapOf("a" to 500L, "b" to -500L)),
        )
    }

    @Test
    fun zero_balance_people_are_left_out() {
        val result = simplifyDebts(mapOf("a" to 1000L, "b" to 0L, "c" to -1000L))
        assertEquals(listOf(Transfer("c", "a", 1000)), result)
    }

    @Test
    fun largest_matched_first() {
        val result = simplifyDebts(mapOf("a" to 700L, "b" to 300L, "c" to -600L, "d" to -400L))
        assertEquals(
            listOf(Transfer("c", "a", 600), Transfer("d", "b", 300), Transfer("d", "a", 100)),
            result,
        )
    }

    @Test
    fun ties_break_by_person_id() {
        val result = simplifyDebts(mapOf("b" to 100L, "a" to 100L, "d" to -100L, "c" to -100L))
        assertEquals(listOf(Transfer("c", "a", 100), Transfer("d", "b", 100)), result)
    }

    @Test
    fun two_person_household() {
        assertEquals(
            listOf(Transfer("a", "b", 1234)),
            simplifyDebts(mapOf("a" to -1234L, "b" to 1234L)),
        )
    }

    @Test
    fun three_person_household_with_one_creditor() {
        val result = simplifyDebts(mapOf("a" to 900L, "b" to -300L, "c" to -600L))
        assertEquals(listOf(Transfer("c", "a", 600), Transfer("b", "a", 300)), result)
    }

    @Test
    fun three_person_chain_collapses_to_two_transfers() {
        val result = simplifyDebts(mapOf("a" to -500L, "b" to 0L, "c" to 500L))
        assertEquals(listOf(Transfer("a", "c", 500)), result)
    }

    @Test
    fun four_person_household_with_uneven_balances() {
        val result = simplifyDebts(mapOf("a" to 1500L, "b" to -200L, "c" to -700L, "d" to -600L))
        assertEquals(
            listOf(Transfer("c", "a", 700), Transfer("d", "a", 600), Transfer("b", "a", 200)),
            result,
        )
    }

    @Test
    fun already_settled_household_needs_nothing() {
        assertEquals(emptyList(), simplifyDebts(mapOf("a" to 0L, "b" to 0L, "c" to 0L, "d" to 0L)))
    }

    @Test
    fun unbalanced_input_is_rejected() {
        assertFailsWith<IllegalArgumentException> { simplifyDebts(mapOf("a" to 100L, "b" to -50L)) }
    }

    @Test
    fun random_balances_settle_with_at_most_n_minus_one_transfers() {
        val random = Random(42)
        repeat(2000) {
            val people = (0 until random.nextInt(2, 9)).map { "p$it" }
            val net = people.associateWith { random.nextLong(-5000, 5000) }.toMutableMap()
            net[people.first()] = net.getValue(people.first()) - net.values.sum()
            val transfers = simplifyDebts(net)

            val settled = net.toMutableMap()
            transfers.forEach {
                assertTrue(it.amountCents > 0)
                settled[it.fromPersonId] = settled.getValue(it.fromPersonId) + it.amountCents
                settled[it.toPersonId] = settled.getValue(it.toPersonId) - it.amountCents
            }
            assertTrue(settled.values.all { it == 0L })
            assertTrue(transfers.size <= maxOf(0, net.values.count { it != 0L } - 1))
            assertEquals(transfers, simplifyDebts(net))
        }
    }
}
