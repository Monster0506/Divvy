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
    fun unbalanced_input_is_rejected() {
        assertFailsWith<IllegalArgumentException> { simplifyDebts(mapOf("a" to 100L, "b" to -50L)) }
    }

    @Test
    fun random_balances_settle_with_at_most_n_minus_one_transfers() {
        val random = Random(42)
        repeat(500) {
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
